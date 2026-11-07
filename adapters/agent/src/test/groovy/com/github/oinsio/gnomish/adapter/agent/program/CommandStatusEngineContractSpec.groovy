package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.Engine
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.EnginePorts
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.Verdict
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
import com.github.oinsio.gnomish.domain.pipeline.CommandLine
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Usage
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import spock.lang.Specification

/**
 * The engine contract check of add-command-executor (task 3.1; FR6, FR7, NFR-R1, NFR-R2, NG6):
 * the command executor's three statuses, built as contract results and turned into the engine's
 * result by the one mapping every contract executor goes through ({@link ProgramResultMapping}, the
 * single-owner row "Contract status → engine result"), are classified by the engine's existing
 * classifier exactly as define-executor-contract specifies — with no engine change. A {@code
 * command} stage's {@code completed} round runs the verify chain; {@code failed/quality} burns an
 * attempt, records and persists the round, skips the verify chain and feeds its reason forward as
 * the next attempt's one finding; {@code failed/infrastructure} burns nothing, records nothing and
 * escalates {@code CannotExecute}. The scripted executor stands in for the command built-in, whose
 * own exit-code mapping is tasks 5.1 and 5.2.
 */
class CommandStatusEngineContractSpec extends Specification {

    static final TaskContext CONTEXT =
    new TaskContext('TASK-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])
    static final String QUALITY_REASON = 'command exited with status 1\nBUILD FAILED: Foo.java:3: error'

    def executor = new ScriptedExecutor()
    def builtinRunner = new ScriptedBuiltinCheckRunner()
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

    static StageDefinition commandStage(int attemptLimit) {
        new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.COMMAND, '', [:], Sandbox.none(), null,
        new CommandLine('./gradlew build')),
        'instructions.md', [
            VerifyChecks.builtin('files_exist', [:])
        ], new AutonomyLimits(attemptLimit), AdvancementMode.AUTO)
    }

    /** A command round's contract result, through the one contract → engine mapping. */
    static ExecutionResult round(int attempt, Status status, FailureClass failureClass = null, String reason = null) {
        def contract = new ExecutorResult(status, failureClass, null,
                reason == null ? null : UntrustedText.subprocess(reason), null, Usage.absent(), [])
        ProgramResultMapping.toEngine('command', new AttemptKey('TASK-1', 'build', attempt), contract, [])
    }

    TaskOutcome run(int attemptLimit) {
        def ports = new EnginePorts(executor, builtinRunner, new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), new ScriptedJudgeVoter(), listener, persistence,
                VirtualTimeEquipment.on(clock, new VirtualSleeper(clock)), RoundIdentity.inPlace())
        new Engine().run(new PipelineDefinition('1', new AutonomyLimits(5), [commandStage(attemptLimit)]),
        CONTEXT, TaskState.atStageStart('build'), new FakeWorkspace(), ports)
    }

    // FR7 (exit 0 side), NG6: a completed command round is one burned attempt whose verify chain runs
    def "FR6, NG6: a completed command round runs the verify chain and the stage completes"() {
        given:
        executor.scripted << round(0, Status.COMPLETED)
        builtinRunner.scripted << new Verdict.Pass()

        when:
        def outcome = run(3)

        then: 'the verify chain ran once, after the round'
        builtinRunner.calls.size() == 1
        timeline.findIndexOf { it instanceof EngineEvent.ExecutionFinished } <
        timeline.findIndexOf { it instanceof EngineEvent.CheckStarted }

        and: 'the stage completed on its one attempt'
        outcome instanceof TaskOutcome.Completed
        executor.requests.size() == 1
        executor.requests[0].feedback().isEmpty()
    }

    // FR6, NFR-R1: failed/quality burns, records and persists the round, skips the verify chain, and the
    //     second attempt's request carries the first's reason as its one finding
    def "FR6, NFR-R1: a failed/quality command round is burned, recorded and fed forward as one finding"() {
        given: 'attempt 1 exits non-zero (failed/quality); attempt 2 completes and its check passes'
        executor.scripted << round(0, Status.FAILED, FailureClass.QUALITY, QUALITY_REASON)
        executor.scripted << round(1, Status.COMPLETED)
        builtinRunner.scripted << new Verdict.Pass()

        when:
        def outcome = run(3)

        then: 'the first round burned one attempt and was recorded as a quality failure'
        def first = persisted[0]
        first.attemptsUsed() == 1
        first.attempts().size() == 1
        first.attempts()[0].result() == AttemptRecord.Result.QUALITY_FAILURE

        and: 'its one check result is the reason as the one finding, under the executor label'
        def recorded = first.attempts()[0].checkResults()
        recorded == [
            new CheckResult(new CheckRef(0, UntrustedText.manifest('executor:build')),
            new Verdict.Fail([
                new Finding(QUALITY_REASON, null, null)
            ]), Duration.ZERO)
        ]

        and: 'the verify chain ran after the second round only'
        builtinRunner.calls.size() == 1
        !timeline.take(timeline.indexOf('persist')).any {
            it instanceof EngineEvent.CheckStarted
        }

        and: 'the second request carries the first round\'s finding as prior feedback'
        executor.requests[1].attempt() == 1
        executor.requests[1].feedback() == recorded

        and: 'the stage completed with two attempts used (the counter resets on advancing; the records stay)'
        outcome instanceof TaskOutcome.Completed
        executor.requests.size() == 2
        persisted.last().attempts()*.result() == [
            AttemptRecord.Result.QUALITY_FAILURE,
            AttemptRecord.Result.PASSED
        ]
    }

    // FR7, NFR-R2: failed/infrastructure records nothing, persists nothing, burns nothing, runs no check,
    //     and escalates CannotExecute naming the reason — no new durable step
    def "FR7, NFR-R2: a failed/infrastructure command round escalates CannotExecute with no attempt burned"() {
        given:
        def reason = "command './gradlew build' could not run: exit 127 (not found)"
        executor.scripted << round(0, Status.FAILED, FailureClass.INFRASTRUCTURE, reason)

        when:
        def outcome = run(3)

        then:
        outcome instanceof TaskOutcome.Escalated
        outcome.report() == new EscalationReport.CannotExecute(UntrustedText.subprocess(reason), [])
        outcome.finalState() == TaskState.atStageStart('build')
        outcome.finalState().attemptsUsed() == 0
        persisted.isEmpty()
        builtinRunner.calls.isEmpty()
        executor.requests.size() == 1
        !timeline.any { it instanceof EngineEvent.AttemptFinished }
    }
}
