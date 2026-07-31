package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The M4 reconcile proof for the {@code take} run (task 6.4 of add-claim-heartbeat, FR10, D10,
 * NFR-C1), driven by {@link TakeCommand} against a REAL tracker adapter and a real local git repo:
 * a task is delivered (branch records {@code Completed}, its cleanup commit strips {@code
 * .gnomish-task/} from the tip), then the tracker is reopened to model "the finish never landed —
 * a dead instance or a dead tracker at the finish line". A second {@code take} of the same ref
 * MUST reconcile: post the deferred final report, transition the task to {@code Finished}, exit
 * with the delivery exit code, and run ZERO engine rounds — no executor call, proven by the
 * reconcile run's tracker thread carrying a {@code FINISH} with no preceding round {@code PROGRESS}
 * marker (a real engine round always emits one on its first attempt, per {@code
 * fix-abort-progress-reset}).
 *
 * <p>Abstract for the same reason as {@link TakeLifecycleReadyToDeliveredSpecBase}: a concrete
 * adapter's seeding, thread-reading, and reopen-to-open all name the concrete adapter type, so
 * they live in a subclass inside {@code adapter.tracker} while this base — which constructs the
 * package-private {@link TakeCommand}/{@link ManualRunAssembly} — stays in {@code app} and touches
 * the tracker only through the {@link Tracker} port. {@link #seededReadyTrackerAndFactory}, {@link
 * #thread}, and {@link #reopenAsReady} are the three seams a subclass fills in.
 *
 * <p>Implements FR10, D10, NFR-C1, M4 of add-claim-heartbeat.
 */
abstract class TakeReconcileLifecycleSpecBase extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    protected static final TaskRef REF = new TaskRef('PROJ-1')

    @TempDir
    Path tempDir

    Path projectDir
    Path worktreesRoot
    Tracker tracker
    TrackerAdapterFactory trackerFactory

    /** @return {@code [Tracker, TrackerAdapterFactory]} for one fresh Ready task seeded at {@link #REF} */
    abstract List seededReadyTrackerAndFactory(TaskRef ref, String title, String body)

    /** @return {@code tracker}'s correspondence thread on {@code ref} as {@code "KIND: text"} lines, oldest first */
    abstract List<String> thread(Tracker tracker, TaskRef ref)

    /**
     * Reopens {@code ref} into a fresh {@code Ready} state, discarding its prior correspondence —
     * models a delivered task whose tracker finish never landed (or was rolled back to open),
     * while its git branch keeps the durable {@code Completed} outcome.
     */
    abstract void reopenAsReady(TaskRef ref, String title, String body)

    def setup() {
        def seeded = seededReadyTrackerAndFactory(REF, 'Add widgets', 'please add widgets')
        tracker = seeded[0] as Tracker
        trackerFactory = seeded[1] as TrackerAdapterFactory

        projectDir = initWorkingRepo(tempDir, 'project')
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.createDirectories(projectDir.resolve('stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/stage.yaml'), '''\
purpose: build it
executor:
  type: agent-cli
  model: claude-fake-main-1
instructions: stages/build/instructions.md
advancement: auto
''')
        Files.writeString(
                projectDir.resolve('.gnomish/config.yaml'),
                '''\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        commitAll(projectDir)
        worktreesRoot = tempDir.resolve('worktrees')
    }

    private TakeCommand newCommand(FactoryProperties factoryProperties) {
        TakeCommandFactory.of(
                newAssembly(factoryProperties),
                worktreesRoot,
                'taskId',
                factoryProperties,
                Clock.fixed(Instant.parse('2026-01-01T00:00:00Z'), ZoneOffset.UTC),
                [github: trackerFactory],
                TrackerValidatorStub.acceptingGithub())
    }

    private static DefaultApplicationArguments args(String... raw) {
        new DefaultApplicationArguments(raw)
    }

    private static int runExitCode(TakeCommand command, DefaultApplicationArguments appArgs) {
        try {
            command.run(appArgs)
            throw new IllegalStateException('take did not exit with a TakeExitCodeException')
        } catch (TakeExitCodeException e) {
            e.exitCode()
        }
    }

    def "M4: a delivered branch with a missing tracker finish reconciles without running any stage"() {
        given: 'a task delivered once — its branch now records Completed with a cleaned-up tip'
        def factoryProperties = FakeAgentSupport.propertiesFor('plain-round')
        def command = newCommand(factoryProperties)
        assert runExitCode(command, args('take', 'PROJ-1', "--dir=$projectDir")) == 0
        assert tracker.fetchTask(REF).state() instanceof TrackerTaskState.Finished

        when: 'the finish is undone in the tracker (dead instance / dead tracker at the finish line) and take runs again'
        reopenAsReady(REF, 'Add widgets', 'please add widgets')
        def secondExit = runExitCode(command, args('take', 'PROJ-1', "--dir=$projectDir"))

        then: 'the reconcile posts the deferred finish and delivers again (exit code 0), task ends Finished'
        secondExit == 0
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.Finished

        and: 'the reconcile run ran zero engine rounds: its thread is claim then final report, with no round PROGRESS in between'
        def entries = thread(tracker, REF)
        entries.size() == 2
        entries[0].startsWith('CLAIM:')
        entries[1].startsWith('FINISH:')
        entries[1].contains('PROJ-1')
        entries[1].contains('Branch: gnomish/PROJ-1')
        entries.every { !it.startsWith('PROGRESS:') }
    }
}
