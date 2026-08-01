package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.slf4j.LoggerFactory
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * {@link TakeCommand} wired end to end for batch mode (tasks 6.2, 6.3 of add-factory-serve):
 * reaching {@link TakeDispatcher#runBatch} with the project's {@link ServeProperties} slots as N,
 * converging on one aggregate exit code (FR2, FR3, D7), and logging the closing checklist summary
 * (NFR-O2, UX3) before the aggregate {@link TakeExitCodeException} is thrown — including the
 * tracker-take delta spec's "Tool failure dominates" scenario, where one ref's uncaught exception
 * must not abort the other refs and its below-10 code must win the aggregate.
 *
 * Implements FR2, FR3, NFR-O2, UX3, D7 of add-factory-serve.
 */
@Timeout(30)
class TakeCommandBatchSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    private static final String INSTANCE_NAME = 'gnomish-factory'

    @TempDir
    Path tempDir

    Path projectDir
    Path worktreesRoot
    Tracker tracker = Mock()

    def setup() {
        projectDir = initWorkingRepo(tempDir, 'project')
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.createDirectories(projectDir.resolve('stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        // Written at both paths (mirrors TakeCommandCredentialScrubSpec's own documented quirk):
        // the pipeline loader's referenced-file check resolves `instructions:` relative to
        // .gnomish/, while the runtime engine (ControlFilePreflight, non-interactive batch mode
        // reaches it — unlike most take specs, which use --interactive and never notice) resolves
        // the same string relative to the workspace root, the task worktree / project root.
        Files.writeString(projectDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/stage.yaml'), '''\
purpose: build it
executor:
  type: agent-cli
  model: model-x
instructions: stages/build/instructions.md
advancement: auto
''')
        commitAll(projectDir)
        worktreesRoot = tempDir.resolve('worktrees')
        Files.writeString(projectDir.resolve('.gnomish/config.yaml'), '''\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        tracker.listOpen() >> []
    }

    private static TrackerAdapterFactory fakeFactory(Tracker t) {
        new TrackerAdapterFactory() {
                    Tracker create(TrackerConfig config, String instanceId) {
                        t
                    }

                    TaskRef expandRef(TrackerConfig config, String rawRef) {
                        throw new UnsupportedOperationException('not used by this fixture')
                    }
                }
    }

    private TakeCommand newCommand(Map<String, TrackerAdapterFactory> registry, ServeProperties serveProperties) {
        TakeCommandFactory.of(
                newAssembly(testProperties(instanceName: INSTANCE_NAME)),
                worktreesRoot,
                'taskId',
                testProperties(instanceName: INSTANCE_NAME),
                Clock.fixed(Instant.parse('2026-01-01T00:00:00Z'), ZoneOffset.UTC),
                registry,
                TrackerValidatorStub.acceptingGithub(),
                serveProperties)
    }

    private static DefaultApplicationArguments args(String... raw) {
        new DefaultApplicationArguments(raw)
    }

    private static TrackerTask trackerTask(TaskRef ref, TrackerTaskState state, String taskId) {
        new TrackerTask(ref, new TaskSnapshot(taskId, 'title', 'body'), state, AbortFacts.none())
    }

    // FR2, FR3: 2+ refs reach batch mode, and the run's aggregate exit code is 0 when every ref
    // delivers.
    def "batch of two delivering refs exits 0"() {
        given:
        def refA = new TaskRef('github:acme/widgets#1')
        def refB = new TaskRef('github:acme/widgets#2')
        // TakeCommand mints a fresh, randomly-suffixed InstanceId per invocation (unlike the
        // dispatcher-level specs, which inject a fixed INSTANCE), so every later fetchTask stub
        // must report THIS run's own claiming instance id, captured from claim() itself — mirrors
        // TakeCommandSpec's own "explicit mode delivering..." fixture pattern.
        String claimedByA = null
        String claimedByB = null
        tracker.fetchTask(refA) >> {
            claimedByA == null
            ? trackerTask(refA, new TrackerTaskState.Ready(), 'PROJ-1')
            : trackerTask(refA, new TrackerTaskState.Working(claimedByA), 'PROJ-1')
        }
        tracker.fetchTask(refB) >> {
            claimedByB == null
            ? trackerTask(refB, new TrackerTaskState.Ready(), 'PROJ-2')
            : trackerTask(refB, new TrackerTaskState.Working(claimedByB), 'PROJ-2')
        }
        tracker.claim(refA, _) >> { TaskRef r, String instanceId -> claimedByA = instanceId; new ClaimResult.Acquired() }
        tracker.claim(refB, _) >> { TaskRef r, String instanceId -> claimedByB = instanceId; new ClaimResult.Acquired() }
        def registry = [github: fakeFactory(tracker)]
        def command = newCommand(registry, new ServeProperties(2, null, null, null))

        when:
        command.run(args('take', refA.id(), refB.id(), "--dir=$projectDir"))

        then:
        def ex = thrown(TakeExitCodeException)
        ex.exitCode() == 0
    }

    // FR3, D7 (exit-code half): one refusal in the batch still produces a non-zero aggregate exit
    // code (15, Skipped), even though the other ref delivered — proving the batch path is actually
    // reached and its result feeds the exit code, not a silent success.
    def "a batch with one Finished ref among Ready refs exits non-zero"() {
        given:
        def refA = new TaskRef('github:acme/widgets#1')
        def refB = new TaskRef('github:acme/widgets#2')
        tracker.fetchTask(refA) >> trackerTask(refA, new TrackerTaskState.Finished(), 'PROJ-1')
        String claimedByB = null
        tracker.fetchTask(refB) >> {
            claimedByB == null
            ? trackerTask(refB, new TrackerTaskState.Ready(), 'PROJ-2')
            : trackerTask(refB, new TrackerTaskState.Working(claimedByB), 'PROJ-2')
        }
        tracker.claim(refB, _) >> { TaskRef r, String instanceId -> claimedByB = instanceId; new ClaimResult.Acquired() }
        def registry = [github: fakeFactory(tracker)]
        def command = newCommand(registry, new ServeProperties(2, null, null, null))

        when:
        command.run(args('take', refA.id(), refB.id(), "--dir=$projectDir"))

        then:
        def ex = thrown(TakeExitCodeException)
        ex.exitCode() == 15
    }

    // Attaches a ListAppender to TakeCommand's own logger before the run, so a captured log event
    // survives even when command.run() throws (as batch mode always does): unlike a
    // try/emit()/finally wrapper, whose finally cannot stop the run's own exception from
    // propagating past the point where the captured list would be returned, this leaves the
    // appender attached for the caller's own when/then to unwind around, and the caller detaches
    // it explicitly once done reading.
    private static ListAppender<ILoggingEvent> attachAppender() {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(TakeCommand)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        return appender
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(TakeCommand)
        logbackLogger.detachAppender(appender)
        appender.stop()
    }

    // Tracker-take delta spec scenario "Tool failure dominates": one ref's short-ref expansion
    // raises an uncaught RuntimeException (task 6.3's tool-failure-capture gap) while the other
    // ref delivers — the run must not abort, and the aggregate exit code must come from the
    // below-10 tool-failure family, beating the delivering ref's 0.
    def "tool failure dominates: one ref's uncaught exception does not abort the batch, and its exit code wins"() {
        given:
        def refB = new TaskRef('github:acme/widgets#2')
        String claimedByB = null
        tracker.fetchTask(refB) >> {
            claimedByB == null
            ? trackerTask(refB, new TrackerTaskState.Ready(), 'PROJ-2')
            : trackerTask(refB, new TrackerTaskState.Working(claimedByB), 'PROJ-2')
        }
        tracker.claim(refB, _) >> { TaskRef r, String instanceId -> claimedByB = instanceId; new ClaimResult.Acquired() }
        // fakeFactory's expandRef always throws UnsupportedOperationException (not used by this
        // fixture) — a short ref like '42' reaches it, so the ref fails for a reason outside this
        // fixture's control, exactly the "tool could not operate" shape.
        def registry = [github: fakeFactory(tracker)]
        def command = newCommand(registry, new ServeProperties(2, null, null, null))
        def appender = attachAppender()

        when:
        command.run(args('take', '42', refB.id(), "--dir=$projectDir"))

        then: 'the other ref still ran and delivered, so the batch was not aborted by the failure'
        def ex = thrown(TakeExitCodeException)

        and: 'the aggregate exit code is the below-10 tool-failure family, not the delivering ref\'s 0'
        ex.exitCode() > 0 && ex.exitCode() < 10

        and: 'the checklist summary names both refs, including the tool failure'
        def summary = appender.list.find { it.level == Level.INFO && it.formattedMessage.contains('batch take:') }
        summary != null
        summary.formattedMessage.contains('42 -> tool failure')
        summary.formattedMessage.contains(refB.id() + ' -> delivered')

        cleanup:
        detachAppender(appender)
    }

    // FR3, NFR-O2, UX3: a mixed batch (one delivered, one skipped) logs a checklist summary naming
    // both refs and their outcomes before the aggregate exit code is thrown.
    def "logs a checklist summary naming every ref and its outcome"() {
        given:
        def refA = new TaskRef('github:acme/widgets#1')
        def refB = new TaskRef('github:acme/widgets#2')
        tracker.fetchTask(refA) >> trackerTask(refA, new TrackerTaskState.Finished(), 'PROJ-1')
        String claimedByB = null
        tracker.fetchTask(refB) >> {
            claimedByB == null
            ? trackerTask(refB, new TrackerTaskState.Ready(), 'PROJ-2')
            : trackerTask(refB, new TrackerTaskState.Working(claimedByB), 'PROJ-2')
        }
        tracker.claim(refB, _) >> { TaskRef r, String instanceId -> claimedByB = instanceId; new ClaimResult.Acquired() }
        def registry = [github: fakeFactory(tracker)]
        def command = newCommand(registry, new ServeProperties(2, null, null, null))
        def appender = attachAppender()

        when:
        command.run(args('take', refA.id(), refB.id(), "--dir=$projectDir"))

        then:
        thrown(TakeExitCodeException)
        def summary = appender.list.find { it.level == Level.INFO && it.formattedMessage.contains('batch take:') }
        summary != null
        summary.formattedMessage.contains('2 ref(s)')
        summary.formattedMessage.contains(refA.id() + ' -> skipped')
        summary.formattedMessage.contains(refB.id() + ' -> delivered')

        cleanup:
        detachAppender(appender)
    }
}
