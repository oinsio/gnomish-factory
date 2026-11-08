package com.github.oinsio.gnomish.domain.engine

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.Unpriced
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
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
 * The failure-class mapping seen from the engine (FR7, FR2 of define-executor-contract; stage-engine
 * "One failure-class mapping"): a fake executor produces each status and class once, and the run's
 * accounting — attempts burned, rounds recorded, the outcome — matches the table row by row. The
 * {@code failed/limit} row is then pinned in full: its own escalation, the bound carried, the round
 * recorded with its usage, one WARN.
 *
 * <p>Implements FR7, FR2 of define-executor-contract.
 */
class ExecutorOutcomeTableSpec extends Specification {

    static final def CONTEXT = new TaskContext('TASK-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])
    static final def TRACE = new ToolTrace(new AttemptKey('TASK-1', 'build', 0), [])
    static final def BOUND = UntrustedText.agent('token bound 1000 reached')
    static final def USAGE = new ExecutorUsage(Duration.ofSeconds(7), [], Unpriced.of(['m-1': new TokenUsage(900, 120, 0, 0)]))

    def executor = new ScriptedExecutor()
    def builtinRunner = new ScriptedBuiltinCheckRunner()
    def persistence = new InMemoryAttemptPersistence()
    def listener = new RecordingEventListener()

    def run() {
        def stage = new StageDefinition('build', 'purpose', [], [], new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', [
            VerifyChecks.builtin('files_exist', [:])
        ], new AutonomyLimits(1), AdvancementMode.AUTO)
        def ports = new EnginePorts(executor, builtinRunner, new ScriptedCommandCheckRunner(), new ScriptedExternalCheckClient(),
                new ScriptedJudgeVoter(), listener, persistence, VirtualTimeEquipment.on(new VirtualClock()), RoundIdentity.inPlace())
        new Engine().run(new PipelineDefinition('1', new AutonomyLimits(5), [stage]), CONTEXT,
        TaskState.atStageStart('build'), new FakeWorkspace(), ports)
    }

    static ExecutionResult result(ExecutionResult.Status status, ExecutionResult.Answer answer = null) {
        new ExecutionResult(status, answer, USAGE, TRACE, [])
    }

    static ExecutionResult.Failed failed(ExecutionResult.FailureClass failureClass) {
        new ExecutionResult.Failed(failureClass, BOUND, [
            new Finding('a finding', null, null)
        ])
    }

    // FR7, FR2: one row per status and class, through a fake executor and the real engine
    // Scenario: pipeline/stage-engine / Executor outcome table verified
    // Scenario: executor/wire-contract / Pending from a role whose caller cannot poll
    def "#row: attempts burned #burned, rounds recorded #recorded, outcome #outcomeKind"() {
        given: 'the executor produces the row once; a completed round passes its one check'
        executor.scripted << input
        builtinRunner.scripted << new Verdict.Pass(null, [])

        when:
        def outcome = run()

        then:
        outcome.finalState().attemptsUsed() == burned
        persistence.entries.size() == recorded
        outcomeKind.isInstance(outcome instanceof TaskOutcome.Escalated ? outcome.report() : outcome)
        executor.requests.size() == 1

        where:
        row | input | burned | recorded | outcomeKind
        'completed' | result(new ExecutionResult.Completed()) | 0 | 1 | TaskOutcome.Completed
        'decision-request' | result(new ExecutionResult.Completed(), new ExecutionResult.DecisionRequest(UntrustedText.agent('q?'), [])) |
        0 | 1 | EscalationReport.DecisionNeeded
        'failed/quality' | result(failed(ExecutionResult.FailureClass.QUALITY)) | 1 | 1 | EscalationReport.AttemptsExhausted
        'failed/infrastructure' | result(failed(ExecutionResult.FailureClass.INFRASTRUCTURE)) | 0 | 0 | EscalationReport.CannotExecute
        'failed/limit' | result(failed(ExecutionResult.FailureClass.LIMIT)) | 0 | 1 | EscalationReport.BudgetExhausted
        'pending (transform)' | result(new ExecutionResult.Pending('k-1')) | 0 | 0 | EscalationReport.CannotExecute
    }

    // FR7: failed/limit escalates BudgetExhausted carrying the bound, burns nothing, records the
    //     round with its usage under the strict ordering, runs no check, and logs one WARN
    // Scenario: pipeline/stage-engine / Budget exhausted is its own escalation
    // Scenario: executor/wire-contract / Limit stop is its own class
    def "a reported limit is its own escalation, recorded with its usage"() {
        given:
        executor.scripted << result(failed(ExecutionResult.FailureClass.LIMIT))
        def logs = LogCaptureSupport.attach(RoundFailures, Level.WARN)

        when:
        def outcome = run()

        then: 'the outcome is BudgetExhausted naming the bound, attemptsUsed unchanged'
        outcome instanceof TaskOutcome.Escalated
        outcome.report() == new EscalationReport.BudgetExhausted(BOUND)
        outcome.finalState().attemptsUsed() == 0

        and: 'the round is recorded with its usage, its stop carrying the bound into the round commit'
        outcome.finalState().attempts().size() == 1
        def round = outcome.finalState().attempts()[0]
        round.result() == AttemptRecord.Result.BUDGET_EXHAUSTED
        round.usage() == [
            Participants.transform(round.usage()[0].executor(), USAGE)
        ]
        round.checkResults().isEmpty()
        round.stop() == new Stop.BudgetExhausted(BOUND)
        persistence.entries*.state == [outcome.finalState()]

        and: 'no check ran, and the round was finished before its persist'
        builtinRunner.calls.isEmpty()
        def kinds = listener.events*.class
        kinds.indexOf(EngineEvent.ExecutionFinished) <kinds.indexOf(EngineEvent.AttemptFinished)

        and: 'one operator line: the budget WARN naming the round and the bound'
        logs.list.size() == 1
        logs.list[0].level == Level.WARN
        logs.list[0].formattedMessage.startsWith(OperatorEvent.EXECUTOR_BUDGET_EXHAUSTED.head())
        logs.list[0].formattedMessage.contains('TASK-1')
        logs.list[0].formattedMessage.contains(BOUND.forLog())

        cleanup:
        logs?.detach()
    }
}
