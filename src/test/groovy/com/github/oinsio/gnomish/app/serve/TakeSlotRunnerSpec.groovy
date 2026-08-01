package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.app.AppAssemblyFixture
import com.github.oinsio.gnomish.app.RunArguments
import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link TakeSlotRunner}, task 4.3 of add-factory-serve: proves the "slot body unchanged"
 * scenario by asserting {@link TakeSlotRunner#run} delegates to the exact same {@code
 * TakeClaimAndWork#dispatchAfterClaim} sequence a single explicit {@code take} would (a fresh
 * claimed task creates a branch and reaches a terminal {@link
 * com.github.oinsio.gnomish.app.take.TakeResult.Delivered}), that the {@code taskId} MDC key is
 * set for the duration of a slot and cleared afterwards on both the success and the failure path,
 * and that a {@link Tracker} failure never escapes {@link TakeSlotRunner#run} (the deliberate
 * exception-swallowing boundary). {@code dispatchAfterClaim}'s own internals — fresh-claim vs.
 * resume, crash-abort, heartbeat lifecycle — are already covered by {@code
 * TakeClaimAndWorkSpec}/{@code TakeBareAutoSpec}; this spec stays a thin "did we plug the pieces
 * together right" check rather than re-testing them.
 *
 * <p>Implements FR1, M2 of add-factory-serve.
 */
class TakeSlotRunnerSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    private static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')
    private static final int ABORT_THRESHOLD = 3
    private static final String MDC_KEY = 'taskId'

    @TempDir
    Path tempDir

    Path cloneDir
    Path worktreesRoot
    def gitRunner = new GitProcessRunner()
    Tracker tracker = Mock()

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'my-project')
        Files.writeString(cloneDir.resolve('instructions.md'), 'build it\n')
        gitRunner.run(cloneDir, 'add', 'instructions.md')
        gitRunner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        worktreesRoot = tempDir.resolve('worktrees-root')
    }

    def cleanup() {
        MDC.remove(MDC_KEY)
    }

    private static TrackerTask trackerTask(String taskId) {
        new TrackerTask(
                new TaskRef(taskId), new TaskSnapshot(taskId, 'title', 'body'),
                new TrackerTaskState.Working(INSTANCE.value()), AbortFacts.none())
    }

    private static StageDefinition stage() {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage()])
    }

    private TakeSlotRunner newSlotRunner() {
        def abortHandler = new AbortHandler(tracker, Clock.systemUTC())
        new TakeSlotRunner(
                newAssembly(), cloneDir, worktreesRoot, pipeline(), abortHandler, ABORT_THRESHOLD, MDC_KEY,
                [], ClaimBeat.NONE, new ClaimLossFlag(), tracker, INSTANCE)
    }

    // Scenario: slot body unchanged — a pre-claimed fresh task dispatches through
    // TakeClaimAndWork#dispatchAfterClaim exactly as a single explicit `take` would: the branch is
    // created and the engine runs to a terminal Delivered result.
    def "runs a pre-claimed fresh task through the same take cycle as an explicit take"() {
        given:
        tracker.fetchTask(new TaskRef('PROJ-1')) >> trackerTask('PROJ-1')
        def slotRunner = newSlotRunner()

        when:
        slotRunner.run(new TaskRef('PROJ-1'))

        then:
        gitRunner.run(cloneDir, 'rev-parse', '--verify', 'gnomish/PROJ-1').exitCode() == 0
        0 * tracker.claim(*_)
    }

    // Scenario: MDC is set to the claimed ref's id for the duration of the run and cleared once it
    // terminates — the success path.
    def "sets and clears the taskId MDC key around a successful slot"() {
        given:
        def slotRunner = newSlotRunner()
        String observedDuringRun = null
        tracker.fetchTask(_) >> {
            observedDuringRun = MDC.get(MDC_KEY)
            trackerTask('PROJ-2')
        }

        when:
        slotRunner.run(new TaskRef('PROJ-2'))

        then:
        observedDuringRun == 'PROJ-2'
        MDC.get(MDC_KEY) == null
    }

    // FR10, NFR-O2 (task 5.4): a slot with a DrainReport attached records its terminal outcome
    //     into it, in addition to the existing log line — the mechanism ServeCommand's drain path
    //     uses to build its closing report.
    def "records its outcome into an attached DrainReport"() {
        given:
        tracker.fetchTask(new TaskRef('PROJ-4')) >> trackerTask('PROJ-4')
        def slotRunner = newSlotRunner()
        def report = new DrainReport()
        slotRunner.attachDrainReport(report)

        when:
        slotRunner.run(new TaskRef('PROJ-4'))

        then:
        report.entries().size() == 1
        report.entries().first().ref() == new TaskRef('PROJ-4')
        report.summary().contains('delivered')
    }

    // FR10: with no DrainReport attached (the ordinary, non-drain path) a slot behaves exactly as
    //     before — no report side effect to worry about.
    def "does not require a DrainReport to be attached"() {
        given:
        tracker.fetchTask(new TaskRef('PROJ-5')) >> trackerTask('PROJ-5')
        def slotRunner = newSlotRunner()

        when:
        slotRunner.run(new TaskRef('PROJ-5'))

        then:
        noExceptionThrown()
    }

    // Task 4.3 / PIT: logOutcome's observable effect is the terminal-outcome log line itself —
    // it is a private method with no injected collaborator, so we capture TakeSlotRunner's
    // Logback logger (same pattern as FeedAutomatonSpec#captureLogs) around a run and assert the
    // "delivered" summary is actually logged, killing the VoidMethodCallMutator that removes the
    // call to logOutcome from run().
    def "logs the delivered outcome via logOutcome after a successful run"() {
        given:
        tracker.fetchTask(new TaskRef('PROJ-6')) >> trackerTask('PROJ-6')
        def slotRunner = newSlotRunner()

        when:
        List<ILoggingEvent> events = captureLogs { slotRunner.run(new TaskRef('PROJ-6')) }

        then:
        events.any {
            it.level == Level.INFO && it.formattedMessage.contains('PROJ-6') && it.formattedMessage.contains('delivered')
        }
    }

    private static List<ILoggingEvent> captureLogs(Closure body) {
        LogbackLogger logbackLogger = (LogbackLogger) LoggerFactory.getLogger(TakeSlotRunner)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        Level original = logbackLogger.level
        logbackLogger.level = Level.DEBUG
        try {
            body()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
            logbackLogger.level = original
        }
        return appender.list
    }

    // Scenario: a Tracker failure (fetchTask throwing) is caught, logged, and does not propagate
    // out of run(TaskRef) — the deliberate exception-swallowing boundary a slot crash must respect
    // so it never takes down the daemon or another slot; MDC is still cleared afterward.
    def "swallows an exception from fetchTask and still clears MDC"() {
        given:
        tracker.fetchTask(new TaskRef('PROJ-3')) >> { throw new RuntimeException('tracker unreachable') }
        def slotRunner = newSlotRunner()

        when:
        slotRunner.run(new TaskRef('PROJ-3'))

        then:
        noExceptionThrown()
        MDC.get(MDC_KEY) == null
    }
}
