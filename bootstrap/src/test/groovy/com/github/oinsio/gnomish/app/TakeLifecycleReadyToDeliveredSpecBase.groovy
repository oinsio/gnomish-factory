package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The first M3 lifecycle end to end, driven by {@link TakeCommand}, against a REAL tracker
 * adapter (not a mock) and a real local git repo: ready -> claim -> work -> delivered with a
 * final report (M3), and the tracker's own correspondence thread alone — with no test-side
 * bookkeeping of its own — tells the whole story of the task: claim, then final report (UX4).
 *
 * <p>Abstract so a concrete adapter's own seeding/thread-reading, both of which necessarily name
 * the concrete adapter type, live in a subclass placed inside {@code adapter.tracker} itself
 * (design D15's own "abstract Spock base class extended per adapter" convention for the port
 * contract suite, reused here): {@code TrackerPortBoundarySpec} (FR1) forbids any class outside
 * {@code adapter.tracker} from depending on a concrete adapter class, and {@code app}'s CLI wiring
 * classes ({@link ManualRunAssembly}, {@link TakeCommand}) are package-private by design, so this
 * base class stays in {@code app} (where it CAN construct them) and only ever touches the tracker
 * through the {@link Tracker} and {@link TrackerAdapterFactory} port types — {@link
 * #seededReadyTrackerAndFactory} and {@link #thread} are the only two seams a subclass fills in
 * with a concrete adapter.
 *
 * <p>The stage is a single {@code agent-cli} stage with no {@code verify} checks (empty Quality
 * Control list trivially passes), backed by the fake agent binary's {@code plain-round} scenario
 * (FR15 of add-agent-executor) rather than a real agent process, mirroring {@code
 * TakeCommandCredentialScrubSpec}'s wiring but swapping its mocked {@code Tracker} for a real one.
 *
 * <p>The second M3 lifecycle (escalate -> human decision -> resume, including resume by a
 * different instance) is out of scope for this spec — see task 6.2.
 *
 * <p>Implements FR1, FR3, FR18, M3, UX4 of add-tracker-port.
 */
abstract class TakeLifecycleReadyToDeliveredSpecBase extends Specification implements BareGitRepoFixture, AppAssemblyFixture, ApplicationArgumentsFixture {

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

    def setup() {
        def seeded = seededReadyTrackerAndFactory(REF, 'Add widgets', 'please add widgets')
        tracker = seeded[0] as Tracker
        trackerFactory = seeded[1] as TrackerAdapterFactory

        projectDir = initWorkingRepo(tempDir, 'project')
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.createDirectories(projectDir.resolve('stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        // Written at both paths: the pipeline loader resolves `instructions:` relative to the
        // .gnomish/ root, while the runtime engine resolves the same string relative to the
        // workspace root (the task worktree) — see TakeCommandCredentialScrubSpec's own note.
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
        // tracker.type is 'github' purely to satisfy TrackerSeamValidator's registered-type
        // check (this fixture passes TrackerValidatorStub.acceptingGithub() as the validator
        // registry, so 'github' is a known-but-permissive type — content isn't under test here) —
        // TakeCommand's own trackerAdapterRegistry below is independent of that seam and is what
        // actually resolves the live Tracker, so this fixture registers the real tracker under
        // the SAME key, 'github', overriding which adapter backs that type for this run.
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
                TaskGitFixture.real(),
                worktreesRoot,
                'taskId',
                factoryProperties,
                Clock.fixed(Instant.parse('2026-01-01T00:00:00Z'), ZoneOffset.UTC),
                [github: trackerFactory],
                MapSecretsProvider.NONE,
                TrackerValidatorStub.acceptingGithubSource(), SandboxLifecyclePass.NONE, ContainerTakeSupport.hostOnly())
    }

    def "ready -> claim -> work -> delivered with a final report, told end to end by the tracker's own thread"() {
        given: 'a Ready task seeded directly in a real tracker, and a fake-agent-backed stage'
        def factoryProperties = FakeAgentSupport.propertiesFor('plain-round')
        def command = newCommand(factoryProperties)

        when: 'take is run against the seeded ref in explicit mode'
        command.run(args('take', 'PROJ-1', "--dir=$projectDir"))

        then: 'the run reaches the Delivered exit code (0), per design D16'
        def ex = thrown(TakeExitCodeException)
        ex.exitCode() == 0

        and: 'the tracker itself ends Finished, with a final report/summary present (FR18, M3)'
        def finalTask = tracker.fetchTask(REF)
        finalTask.state() instanceof TrackerTaskState.Finished

        and: 'the final report names the task and the branch, proving it is a real StatusReport render (D11)'
        def entries = thread(tracker, REF)
        def finishEntry = entries.find { it.startsWith('FINISH:') }
        finishEntry != null
        finishEntry.contains('PROJ-1')
        finishEntry.contains('Stage: pipeline complete')
        finishEntry.contains('Branch: gnomish/PROJ-1')

        and: 'the tracker thread alone tells the story in order: claim, durable-progress marker, then final report (UX4, FR2 of fix-abort-progress-reset) — no other source consulted'
        entries.size() == 3
        entries[0].startsWith('CLAIM:')
        entries[0].contains('claimed by')
        entries[1].startsWith('PROGRESS:')
        entries[2].startsWith('FINISH:')
    }
}
