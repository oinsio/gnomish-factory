package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence
import com.github.oinsio.gnomish.domain.engine.port.BuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.port.CommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import java.time.InstantSource
import spock.lang.Specification

/**
 * Engine pre-flight re-escalates from the record (design D3): a run whose current stage's last
 * recorded round carries a stop — the state a lost park leaves behind — returns the matching
 * Escalated report rebuilt from that round, with identical content to the live escalation, no
 * port invoked and {@code attemptsUsed} unchanged. A history the resumed write emptied runs the
 * stage. Order: PipelineMismatch → AwaitingApproval → recorded stop → attempt limit.
 * Implements FR6 of make-checkpoint-gate-durable.
 */
class EnginePreflightRecordedStopSpec extends Specification {

    static final def CONTEXT = new TaskContext('TASK-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])
    static final def TRACE = new ToolTrace(new AttemptKey('TASK-1', 'build', 0), [])

    def executor = new ScriptedExecutor()
    def builtinRunner = new ScriptedBuiltinCheckRunner()
    def persistence = new InMemoryAttemptPersistence()
    def clock = new VirtualClock()

    EnginePorts livePorts() {
        new EnginePorts(executor, builtinRunner, new ScriptedCommandCheckRunner(), new ScriptedExternalCheckClient(),
                new ScriptedJudgeVoter(), new RecordingEventListener(), persistence, VirtualTimeEquipment.on(clock))
    }

    /** Ports whose every call throws, so any call the re-escalation makes surfaces at once. */
    EnginePorts throwingPorts(RecordingEventListener listener) {
        new EnginePorts(throwing(StageExecutor), throwing(BuiltinCheckRunner), throwing(CommandCheckRunner),
                throwing(ExternalCheckClient), throwing(JudgeVoter), listener, throwing(AttemptPersistence),
                new TimeEquipment(throwing(InstantSource), throwing(Sleeper)), throwing(AttemptDelivery))
    }

    def throwing(Class<?> type) {
        Mock(type) {
            _ >> {
                throw new IllegalStateException("${type.simpleName} invoked")
            }
        }
    }

    static PipelineDefinition pipeline(int attemptLimit) {
        new PipelineDefinition('1', new AutonomyLimits(5), [
            new StageDefinition('build', 'purpose', [], [],
            new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
            'instructions.md', [
                new VerifyCheck.Builtin('files_exist', [:])
            ],
            new AutonomyLimits(attemptLimit), AdvancementMode.AUTO)
        ])
    }

    static ExecutionResult.Completed completed() {
        new ExecutionResult.Completed(ExecutorUsage.none(), TRACE, [])
    }

    static ExecutionResult.DecisionNeeded decision() {
        def options = ['postgres', 'mysql'].collect { UntrustedText.agent(it) }
        new ExecutionResult.DecisionNeeded(UntrustedText.agent('which db?'), options, ExecutorUsage.none(), TRACE, [])
    }

    static AttemptRecord round(int number, AttemptRecord.Result result, Stop stop) {
        new AttemptRecord(number, result, Instant.EPOCH, [], ExecutorUsage.none(), JudgeUsage.none(), [], stop)
    }

    static final def DECISION_STOP = new Stop.DecisionNeeded(UntrustedText.agent('which db?'), [UntrustedText.agent('pg')])

    // FR6: a lost park after a stop — the round commit landed, the park did not — re-raises the
    //      very escalation the live loop produced, from the persisted state alone, reaching no port
    def "a lost park after a #kind round re-escalates from the record with identical content"() {
        given: 'a live run whose round ends in the stop and persists its round commit'
        script.call(this)
        def live = new Engine().run(pipeline(3), CONTEXT, TaskState.atStageStart('build'), new FakeWorkspace(), livePorts())
        def persisted = persistence.entries.last().state

        when: 'the next run starts from the persisted state, with every port throwing'
        def listener = new RecordingEventListener()
        def outcome = new Engine().run(pipeline(3), CONTEXT, persisted, new FakeWorkspace(), throwingPorts(listener))

        then: 'the outcome is Escalated with the very report the live loop produced'
        live instanceof TaskOutcome.Escalated
        outcome instanceof TaskOutcome.Escalated
        reportType.isInstance(outcome.report())
        outcome.report() == live.report()

        and: 'the recorded state is returned unchanged: no round recorded, no attempt burned'
        outcome.finalState() == persisted
        outcome.finalState().attemptsUsed() == live.finalState().attemptsUsed()
        outcome.finalState().attempts().size() == 1

        and: 'no port was invoked, and both bookends fired'
        0 * _
        listener.events*.class == [
            EngineEvent.RunStarted,
            EngineEvent.TaskFinished
        ]

        where:
        kind | reportType | script
        'DecisionNeeded' | EscalationReport.DecisionNeeded | { s ->
            s.executor.scripted << decision()
        }
        'CannotVerify' | EscalationReport.CannotVerify | { s ->
            s.executor.scripted << completed(); s.builtinRunner.scripted << new Verdict.CannotVerify(UntrustedText.subprocess('binary not found'), UntrustedText.subprocess('no such tool'))
        }
    }

    // FR6: a consumed stop — the history emptied by resetAttempts(), or a later round without a
    //      stop — is not re-raised: the stage runs from the recorded history
    def "a state whose last round carries no stop runs the stage: #history"() {
        given: 'the stage will pass on its next round'
        executor.scripted << completed()
        builtinRunner.scripted << new Verdict.Pass()

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(3), CONTEXT, state, new FakeWorkspace(), livePorts())

        then: 'the stage ran and completed the pipeline'
        outcome instanceof TaskOutcome.Completed
        persistence.entries.size() == 1

        where:
        history | state
        'reset by resetAttempts()' | stopped(DECISION_STOP).resetAttempts()
        'reset by startOfStage()' | stopped(DECISION_STOP).resetAttempts().startOfStage()
        'a later round without stop' | new TaskState(new Position.AtStage('build'), 1, [
            round(0, AttemptRecord.Result.DECISION_NEEDED, DECISION_STOP),
            round(1, AttemptRecord.Result.QUALITY_FAILURE, Stop.none())
        ], ExecutorUsage.none())
    }

    // FR6: the recorded stop precedes the attempt limit — a spent counter does not mask the
    //      question the record carries
    def "a recorded stop re-escalates before the attempt limit"() {
        given: 'a stop recorded on a state whose counter already reached the limit'
        def state = new TaskState(new Position.AtStage('build'), 1, [
            round(0, AttemptRecord.Result.QUALITY_FAILURE, Stop.none()),
            round(1, AttemptRecord.Result.DECISION_NEEDED, DECISION_STOP)
        ], ExecutorUsage.none())

        when:
        def outcome = new Engine().run(pipeline(1), CONTEXT, state, new FakeWorkspace(), throwingPorts(new RecordingEventListener()))

        then: 'the stop is re-raised, not AttemptsExhausted'
        outcome == new TaskOutcome.Escalated(state, new EscalationReport.DecisionNeeded(DECISION_STOP.question(), DECISION_STOP.options()))
        0 * _
    }

    // FR9 of add-stage-engine: a stale stage name is a PipelineMismatch before the recorded stop
    def "a recorded stop on a stage the pipeline no longer declares escalates as PipelineMismatch"() {
        given:
        def state = new TaskState(new Position.AtStage('gone'), 0, [
            round(0, AttemptRecord.Result.DECISION_NEEDED, DECISION_STOP)
        ], ExecutorUsage.none())

        when:
        def outcome = new Engine().run(pipeline(3), CONTEXT, state, new FakeWorkspace(), throwingPorts(new RecordingEventListener()))

        then:
        outcome.report() instanceof EscalationReport.PipelineMismatch
        0 * _
    }

    static TaskState stopped(Stop stop) {
        new TaskState(new Position.AtStage('build'), 0, [
            round(0, AttemptRecord.Result.DECISION_NEEDED, stop)
        ], ExecutorUsage.none())
    }
}
