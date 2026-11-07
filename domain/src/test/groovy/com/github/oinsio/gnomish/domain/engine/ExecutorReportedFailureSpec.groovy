package com.github.oinsio.gnomish.domain.engine

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import spock.lang.Specification

/**
 * The executor-reported statuses the reshaped {@link ExecutionResult} carries (FR7 of
 * define-executor-contract, design D4): a {@code failed/quality} round is recorded, burns an
 * attempt, is persisted before its finish event, runs no check, and feeds its findings to the next
 * request; {@code failed/infrastructure} and {@code pending} record nothing and escalate {@code
 * CannotExecute} through the row a thrown executor failure takes, with one WARN of their own
 * ({@code failed/limit} is {@link BudgetExhaustedSpec}'s).
 *
 * <p>Implements FR7 of define-executor-contract.
 */
class ExecutorReportedFailureSpec extends Specification {

    static final def CONTEXT = new TaskContext('TASK-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])
    static final def FINDING = new Finding('the migration drops a column', 'V3.sql:12', 'irreversible')

    def executor = new ScriptedExecutor()
    def builtinRunner = new ScriptedBuiltinCheckRunner()
    def commandRunner = new ScriptedCommandCheckRunner()
    def externalClient = new ScriptedExternalCheckClient()
    def judgeVoter = new ScriptedJudgeVoter()
    def clock = new VirtualClock()
    List<Object> timeline = []
    List<TaskState> persisted = []
    EngineEventListener listener = { EngineEvent event ->
        timeline << event
    } as EngineEventListener
    AttemptPersistence persistence = { String taskId, TaskState state, ToolTrace trace ->
        persisted << state
        timeline << 'persist'
    } as AttemptPersistence

    EnginePorts ports() {
        new EnginePorts(executor, builtinRunner, commandRunner, externalClient, judgeVoter,
                listener, persistence, VirtualTimeEquipment.on(clock, new VirtualSleeper(clock)), RoundIdentity.inPlace())
    }

    static StageDefinition stage(int attemptLimit) {
        new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', [
            VerifyChecks.builtin('files_exist', [:])
        ], new AutonomyLimits(attemptLimit),
        AdvancementMode.AUTO)
    }

    static ToolTrace trace(int round) {
        new ToolTrace(new AttemptKey('TASK-1', 'build', round), [])
    }

    static ExecutionResult failed(ExecutionResult.FailureClass failureClass, int round, List<Finding> findings = [FINDING]) {
        new ExecutionResult(new ExecutionResult.Failed(failureClass, UntrustedText.agent('reported ' + failureClass), findings),
                null, ExecutorUsage.none(), trace(round), [])
    }

    def run(int attemptLimit) {
        new Engine().run(new PipelineDefinition('1', new AutonomyLimits(5), [stage(attemptLimit)]),
        CONTEXT, TaskState.atStageStart('build'), new FakeWorkspace(), ports())
    }

    // FR7: failed/quality burns an attempt, is recorded and persisted before AttemptFinished, runs
    //     no check, and attempt 2's request carries the finding
    // Scenario: pipeline/stage-engine / Executor-reported quality failure
    def "an executor-reported quality failure is a recorded, burned round whose finding is fed forward"() {
        given: 'attempt 1 reports failed/quality with one finding; attempt 2 completes and its check passes'
        executor.scripted << failed(ExecutionResult.FailureClass.QUALITY, 0)
        executor.scripted << ExecutionResult.completed(ExecutorUsage.none(), trace(1), [])
        builtinRunner.scripted << new Verdict.Pass()

        when:
        def outcome = run(3)

        then: 'the first round burned one attempt and was recorded as a quality failure'
        def first = persisted[0]
        first.attemptsUsed() == 1
        first.attempts().size() == 1
        first.attempts()[0].result() == AttemptRecord.Result.QUALITY_FAILURE
        first.attempts()[0].stop() == Stop.none()

        and: 'its one check result carries the finding under the executor label, not a check that ran'
        def recorded = first.attempts()[0].checkResults()
        recorded == [
            new CheckResult(new CheckRef(0, UntrustedText.manifest('executor:build')), new Verdict.Fail([FINDING]), Duration.ZERO)
        ]

        and: 'no check ran for that round: the only check call is the second round\'s'
        builtinRunner.calls.size() == 1
        !timeline.take(timeline.indexOf('persist')).any {
            it instanceof EngineEvent.CheckStarted
        }

        and: 'the round was persisted strictly before its AttemptFinished, which precedes the next round'
        def started = timeline.findIndexOf {
            it instanceof EngineEvent.AttemptStarted
        }
        def executed = timeline.findIndexOf {
            it instanceof EngineEvent.ExecutionFinished
        }
        def persist = timeline.indexOf('persist')
        def finished = timeline.findIndexOf {
            it instanceof EngineEvent.AttemptFinished
        }
        def nextStarted = timeline.findIndexOf(started + 1) {
            it instanceof EngineEvent.AttemptStarted
        }
        started <executed && executed <persist && persist <finished && finished <nextStarted

        and: 'attempt 2 carries the finding as its feedback'
        executor.requests[1].attempt() == 1
        executor.requests[1].feedback() == recorded

        and: 'the stage then passed'
        outcome instanceof TaskOutcome.Completed
    }

    // FR7: a quality failure on the last permitted attempt exhausts the stage like a failed check
    def "an executor-reported quality failure at the limit escalates attempts exhausted"() {
        given:
        executor.scripted << failed(ExecutionResult.FailureClass.QUALITY, 0)

        when:
        def outcome = run(1)

        then:
        outcome instanceof TaskOutcome.Escalated
        outcome.report() == new EscalationReport.AttemptsExhausted(1)
        outcome.finalState().attemptsUsed() == 1
        persisted.size() == 1
        builtinRunner.calls.isEmpty()
    }

    // FR7: infrastructure and pending (a stage cannot poll) record nothing, burn nothing, run no
    //     check, escalate CannotExecute with the reason and denials, and log one WARN, no ERROR
    def "#status records nothing and escalates CannotExecute naming #cause"() {
        given:
        def denial = new Finding('egress denied: evil.example.com:443', null, null)
        executor.scripted << new ExecutionResult(status, null, ExecutorUsage.none(), trace(0), [denial])
        def logs = LogCaptureSupport.attach(RoundFailures, Level.WARN)

        when:
        def outcome = run(3)

        then:
        outcome instanceof TaskOutcome.Escalated
        outcome.report() == new EscalationReport.CannotExecute(cause, [denial])
        outcome.finalState() == TaskState.atStageStart('build')
        persisted.isEmpty()
        builtinRunner.calls.isEmpty()
        !timeline.any {
            it instanceof EngineEvent.AttemptFinished
        }
        // NFR-O2 of define-executor-contract: the unrecorded round still has its round line
        timeline.findAll {
            it instanceof EngineEvent.ExecutionFinished
        }*.status() == [status]

        and: 'the reported failure has exactly one operator line: a WARN naming the round, no ERROR'
        logs.list.size() == 1
        logs.list[0].level == Level.WARN
        logs.list[0].formattedMessage.startsWith(OperatorEvent.EXECUTOR_REPORTED_FAILURE.head())
        logs.list[0].formattedMessage.contains('TASK-1')
        logs.list[0].formattedMessage.contains(cause.forLog())

        cleanup:
        logs?.detach()

        where:
        status | cause
        new ExecutionResult.Failed(ExecutionResult.FailureClass.INFRASTRUCTURE, UntrustedText.agent('box gone'), []) | UntrustedText.agent('box gone')
        new ExecutionResult.Pending('k-1') | ExecutorOutcomeClassifier.UNPOLLABLE
    }

    // FR7: the pending reason names the unsupported pending and does not quote the executor's key
    def "the pending escalation names the unsupported pending"() {
        expect:
        ExecutorOutcomeClassifier.UNPOLLABLE.forLog().contains('unsupported pending')
    }
}
