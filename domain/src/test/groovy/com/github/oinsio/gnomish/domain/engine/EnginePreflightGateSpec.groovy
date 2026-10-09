package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence
import com.github.oinsio.gnomish.domain.engine.port.BuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.port.CommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import java.time.InstantSource
import spock.lang.Specification

/**
 * Engine pre-flight at a gate (design D1): a run whose recorded position is
 * {@code AwaitingApproval(stage)} returns {@code Paused(state, stage)} framed by RunStarted and
 * TaskFinished, and reaches no execution, verification or persistence port — every one of them
 * throws here, so any call would surface. A gate naming a stage the pipeline no longer declares
 * is a PipelineMismatch first. Implements FR2 of make-checkpoint-gate-durable; FR9 of
 * add-stage-engine.
 */
class EnginePreflightGateSpec extends Specification {

    static final def CONTEXT = new TaskContext('TASK-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])

    def executor = throwing(StageExecutor)
    def builtinRunner = throwing(BuiltinCheckRunner)
    def commandRunner = throwing(CommandCheckRunner)
    def externalClient = throwing(ExternalCheckClient)
    def judgeVoter = throwing(JudgeVoter)
    def persistence = throwing(AttemptPersistence)
    def clock = throwing(InstantSource)
    def sleeper = throwing(Sleeper)
    def delivery = throwing(AttemptDelivery)
    def listener = new RecordingEventListener()

    /** A port mock whose every call throws, so any call the engine makes surfaces at once. */
    def throwing(Class<?> type) {
        Mock(type) {
            _ >> {
                throw new IllegalStateException("${type.simpleName} invoked")
            }
        }
    }

    EnginePorts ports() {
        new EnginePorts(executor, builtinRunner, commandRunner, externalClient, judgeVoter, listener,
                persistence, clock, sleeper, delivery)
    }

    static StageDefinition stage(String name) {
        new StageDefinition(name, 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', [], new AutonomyLimits(3), AdvancementMode.MANUAL)
    }

    static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [
            stage('plan'),
            stage('build'),
            stage('review')
        ])
    }

    static TaskState atGate(String gate) {
        def round = new AttemptRecord(0, AttemptRecord.Result.PASSED, Instant.EPOCH, [], ExecutorUsage.none(), JudgeUsage.none(), [], Stop.none())
        new TaskState(new Position.AwaitingApproval(gate), 1, [round], ExecutorUsage.none())
    }

    // FR2 of make-checkpoint-gate-durable: a run from a gate pauses again with the recorded state
    //      unchanged, framed by the bookends, reaching no port — for a middle and a last stage
    def "a run from AwaitingApproval at the #placement stage pauses again and invokes no port"() {
        given: 'the state a passing manual round persisted, held at the gate'
        def state = atGate(gate)

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(), CONTEXT, state, new FakeWorkspace(), ports())

        then: 'the outcome is Paused naming the gate, carrying the recorded state unchanged'
        outcome == new TaskOutcome.Paused(state, gate)
        outcome.finalState().is(state)

        and: 'no execution, verification, persistence or timing port was invoked'
        0 * _

        and: 'exactly RunStarted at the gate, then TaskFinished with the very outcome'
        listener.events.size() == 2
        def started = listener.events[0] as EngineEvent.RunStarted
        started.position() == new Position.AwaitingApproval(gate)
        started.attemptsUsed() == 1
        (listener.events[1] as EngineEvent.TaskFinished).outcome().is(outcome)

        where:
        placement | gate
        'middle' | 'build'
        'last' | 'review'
    }

    // FR9 of add-stage-engine: a gate naming a stage the pipeline no longer declares is stale —
    //      PipelineMismatch comes before the gate, still reaching no port
    def "a gate naming a stage absent from the pipeline escalates as PipelineMismatch"() {
        given: 'a gate whose stage the current pipeline no longer declares'
        def state = atGate('gone')

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(), CONTEXT, state, new FakeWorkspace(), ports())

        then: 'the outcome is Escalated(PipelineMismatch) naming the stale stage'
        outcome instanceof TaskOutcome.Escalated
        outcome.finalState().is(state)
        outcome.report() instanceof EscalationReport.PipelineMismatch
        outcome.report().staleStage().forLog() == 'gone'

        and: 'no port was invoked, and both bookends fired'
        0 * _
        listener.events*.class == [
            EngineEvent.RunStarted,
            EngineEvent.TaskFinished
        ]
    }
}
