package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.app.AppAssemblyFixture
import com.github.oinsio.gnomish.app.ContainerTakeSupport
import com.github.oinsio.gnomish.app.TaskGitFixture
import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
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
import com.github.oinsio.gnomish.logtext.ShutdownPhase
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import com.github.oinsio.gnomish.serveobservability.OutcomeCounts
import com.github.oinsio.gnomish.serveobservability.RunSummaryAccumulator
import com.github.oinsio.gnomish.serveobservability.json.LedgerJsonMapper
import com.github.oinsio.gnomish.serveobservability.writer.LedgerAppender
import com.github.oinsio.gnomish.serveobservability.writer.RotatingLedgerAppender
import com.github.oinsio.gnomish.serveobservability.writer.TaskOutcomeLedgerWriter
import com.github.oinsio.gnomish.status.AnchorLog
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
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

    private static TrackerTask workingTask(String taskId) {
        new TrackerTask(
                new TaskRef(taskId), new TaskSnapshot(taskId, 'title', 'body'),
                new TrackerTaskState.Working(INSTANCE.value()), AbortFacts.none(), false)
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
        // The fake agent binary (plain-round: one delivering round) instead of the default
        // `claude`: the stage's AGENT_CLI executor really spawns this binary, and CI has no real
        // claude on PATH.
        def properties = testProperties(
                agentCliBinary: FakeAgentSupport.propertiesFor('plain-round').agentCliBinary())
        new TakeSlotRunner(
                newAssembly(properties), TaskGitFixture.real(), cloneDir, worktreesRoot, pipeline(), abortHandler, ABORT_THRESHOLD, MDC_KEY,
                [], ClaimBeat.NONE, new ClaimLossFlag(), tracker, INSTANCE, ContainerTakeSupport.hostOnly(),
                new ClaimEpochBook())
    }

    // Scenario: slot body unchanged — a pre-claimed fresh task dispatches through
    // TakeClaimAndWork#dispatchAfterClaim exactly as a single explicit `take` would: the branch is
    // created and the engine runs to a terminal Delivered result.
    def "runs a pre-claimed fresh task through the same take cycle as an explicit take"() {
        given:
        tracker.fetchTask(new TaskRef('PROJ-1')) >> workingTask('PROJ-1')
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
            workingTask('PROJ-2')
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
        tracker.fetchTask(new TaskRef('PROJ-4')) >> workingTask('PROJ-4')
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

    // FR13, design D6 (task 4.5): a slot with a RunSummaryAccumulator attached records its
    //     terminal result into it, beside the DrainReport call — the in-memory totals a drain
    //     run's runSummary ledger line is built from once it completes.
    def "accumulates its outcome into an attached RunSummaryAccumulator"() {
        given:
        tracker.fetchTask(new TaskRef('PROJ-8')) >> workingTask('PROJ-8')
        def slotRunner = newSlotRunner()
        def accumulator = new RunSummaryAccumulator()
        slotRunner.attachRunSummaryAccumulator(accumulator)

        when:
        slotRunner.run(new TaskRef('PROJ-8'))

        then:
        accumulator.counts() == new OutcomeCounts(1, 0, 0, 0)
    }

    // FR13: with no RunSummaryAccumulator attached (the ordinary, non-drain path) a slot behaves
    //     exactly as before — no accumulation side effect to worry about.
    def "does not require a RunSummaryAccumulator to be attached"() {
        given:
        tracker.fetchTask(new TaskRef('PROJ-9')) >> workingTask('PROJ-9')
        def slotRunner = newSlotRunner()

        when:
        slotRunner.run(new TaskRef('PROJ-9'))

        then:
        noExceptionThrown()
    }

    // FR11, design D6 (task 4.3): a slot with a TaskOutcomeLedgerWriter attached appends its
    //     terminal outcome as a taskOutcome ledger line, with startedAt read from the SlotLedger
    //     entry assigned before the slot ran — exactly the sequence FeedCycle drives in production
    //     (assign, then start the slot thread, release only after it returns).
    def "appends a taskOutcome ledger line via an attached TaskOutcomeLedgerWriter"() {
        given:
        tracker.fetchTask(new TaskRef('PROJ-7')) >> workingTask('PROJ-7')
        def slotRunner = newSlotRunner()
        def slotLedger = new SlotLedger(1)
        def ref = new TaskRef('PROJ-7')
        slotLedger.assign(ref)
        def instance = new InstanceInfo('gnomish-ab12cd', 'worker-1', '0.1.0')
        def appender = new RotatingLedgerAppender(
                new LedgerAppender(tempDir.resolve('placeholder'), new LedgerJsonMapper()),
                tempDir, 'gnomish', Clock.systemUTC())
        slotRunner.attachLedgerWriter(new TaskOutcomeLedgerWriter(slotLedger, appender, instance, Clock.systemUTC()))

        when:
        slotRunner.run(ref)

        then:
        def ledgerFile = ObservabilityPaths.ledgerFile(tempDir, 'gnomish', LocalDate.now(ZoneOffset.UTC))
        def lines = Files.readString(ledgerFile).split('\n').findAll {
            !it.isBlank()
        }
        lines.size() == 1
        lines[0].contains('"taskId":"PROJ-7"')
        lines[0].contains('"outcome":"delivered"')
    }

    // FR10: with no DrainReport attached (the ordinary, non-drain path) a slot behaves exactly as
    //     before — no report side effect to worry about.
    def "does not require a DrainReport to be attached"() {
        given:
        tracker.fetchTask(new TaskRef('PROJ-5')) >> workingTask('PROJ-5')
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
    // Task 4.3 of harden-logging-observability: the per-outcome line survives as the DEBUG detail
    // carrier — the delivery's own free text has no room in the summary's fixed vocabulary — while
    // the level-bearing statement of the outcome moved to the summary (the feature below).
    def "logs the delivered outcome detail via logOutcome after a successful run"() {
        given:
        tracker.fetchTask(new TaskRef('PROJ-6')) >> workingTask('PROJ-6')
        def slotRunner = newSlotRunner()

        when:
        List<ILoggingEvent> events = captureLogs {
            slotRunner.run(new TaskRef('PROJ-6'))
        }

        then:
        events.any {
            it.level == Level.DEBUG && it.formattedMessage.contains('PROJ-6') && it.formattedMessage.contains('delivered')
        }

        and: 'and nothing above DEBUG duplicates it — one outcome, one level-bearing line'
        !events.any {
            it.level.isGreaterOrEqual(Level.INFO) && it.formattedMessage.contains('delivered')
        }
    }

    // FR3 of harden-logging-observability: exactly one canonical summary per terminal result, in
    // the shared form, at the level the outcome warrants.
    def "emits exactly one canonical task summary for a terminal result"() {
        given:
        tracker.fetchTask(new TaskRef('PROJ-9')) >> workingTask('PROJ-9')
        def slotRunner = newSlotRunner()
        def capture = LogCaptureSupport.attach(AnchorLog)

        when:
        slotRunner.run(new TaskRef('PROJ-9'))

        then:
        def summaries = capture.list.findAll {
            it.formattedMessage.startsWith('task summary:')
        }
        summaries.size() == 1
        summaries[0].level == Level.INFO
        summaries[0].formattedMessage.contains('outcome=delivered')
        summaries[0].formattedMessage.contains('attempts=')
        summaries[0].formattedMessage.contains('wall=')

        cleanup:
        capture.detach()
    }

    // FR3: the crash boundary is the one terminal path with no result to read, and it is exactly
    // where a missing summary would leave the grep story unfinished. The line states what it knows
    // — the outcome and the wall time — and fabricates nothing else.
    def "emits a summary for a task that leaves through the crash boundary"() {
        given:
        ShutdownPhase.reset()
        tracker.fetchTask(new TaskRef('PROJ-10')) >> {
            throw new IllegalStateException('tracker unreachable')
        }
        def capture = LogCaptureSupport.attach(AnchorLog)

        when:
        newSlotRunner().run(new TaskRef('PROJ-10'))

        then:
        def summaries = capture.list.findAll {
            it.formattedMessage.startsWith('task summary:')
        }
        summaries.size() == 1
        summaries[0].level == Level.WARN
        summaries[0].formattedMessage.contains('outcome=aborted')
        summaries[0].formattedMessage.contains('attempts=0')
        summaries[0].formattedMessage.contains('tokens=unreported')

        cleanup:
        capture.detach()
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
        tracker.fetchTask(new TaskRef('PROJ-3')) >> {
            throw new RuntimeException('tracker unreachable')
        }
        def slotRunner = newSlotRunner()

        when:
        slotRunner.run(new TaskRef('PROJ-3'))

        then:
        noExceptionThrown()
        MDC.get(MDC_KEY) == null
    }

    // FR9 of harden-logging-observability: outside a stop, a slot dying uncaught is exactly what
    // ERROR is for — work was lost and nobody asked for it.
    def "FR9: a crash outside the shutdown phase stays an ERROR with its stack"() {
        given:
        ShutdownPhase.reset()
        tracker.fetchTask(new TaskRef('PROJ-4')) >> {
            throw new IllegalStateException('tracker unreachable')
        }
        def capture = LogCaptureSupport.attach(TakeSlotRunner)

        when:
        newSlotRunner().run(new TaskRef('PROJ-4'))

        then:
        def crash = capture.list.find {
            it.formattedMessage.contains('crashed uncaught')
        }
        crash.level == Level.ERROR
        crash.throwableProxy != null

        cleanup:
        capture.detach()
    }

    // FR9, factory-serve "Shutdown-caused death is not an alarm": the same death during the stop is
    // the stop doing its job. One line, WARN, no stack — the stack would describe the shutdown.
    def "FR9: a crash during the shutdown phase is one WARN without a stack"() {
        given:
        ShutdownPhase.begin()
        tracker.fetchTask(new TaskRef('PROJ-5')) >> {
            throw new IllegalStateException('interrupted by the stop')
        }
        def capture = LogCaptureSupport.attach(TakeSlotRunner)

        when:
        newSlotRunner().run(new TaskRef('PROJ-5'))

        then: 'the death is attributed to the stop, and no ERROR blames the application'
        def lines = capture.list.findAll {
            it.formattedMessage.contains('PROJ-5')
        }
        lines.size() == 1
        lines[0].level == Level.WARN
        lines[0].throwableProxy == null
        lines[0].formattedMessage.contains('stopped by the daemon shutdown')
        lines[0].formattedMessage.contains('IllegalStateException')

        cleanup:
        capture.detach()
        ShutdownPhase.reset()
    }
}
