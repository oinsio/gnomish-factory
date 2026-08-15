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
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import spock.lang.Specification

/**
 * Engine pre-flight, task 5.1 — the entry point run(definition, context, state,
 * workspace, ports) before any stage attempt runs (FR1). Covers the three pre-flight
 * terminals that reach neither the executor nor the persistence port: an immediate
 * Completed from PipelineEnd (FR8), a PipelineMismatch escalation for a stale AtStage
 * name (FR9), and an AttemptsExhausted escalation when attemptsUsed >= the resolved
 * limit on entry (FR5) — each still emitting the RunStarted and TaskFinished bookends
 * (FR12). The stage attempt loop (task 5.2) is out of scope here. Implements FR1, FR5,
 * FR8, FR9, FR12 of add-stage-engine.
 */
class EngineSpec extends Specification {

    static final def WORKSPACE = new FakeWorkspace()
    static final def CONTEXT = new TaskContext('TASK-1', 'title', 'body', [])

    def executor = new ScriptedExecutor()
    def persistence = new InMemoryAttemptPersistence()
    def listener = new RecordingEventListener()
    def clock = new VirtualClock()
    def sleeper = new VirtualSleeper(clock)

    EnginePorts ports() {
        new EnginePorts(executor, new ScriptedBuiltinCheckRunner(), new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), new ScriptedJudgeVoter(), listener, persistence, clock, sleeper)
    }

    static StageDefinition stage(String name, int attemptLimit) {
        new StageDefinition(name, 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', [], new AutonomyLimits(attemptLimit), AdvancementMode.AUTO)
    }

    static PipelineDefinition pipeline(StageDefinition... stages) {
        new PipelineDefinition('1', new AutonomyLimits(3), stages.toList())
    }

    // FR8: a run positioned at PipelineEnd completes immediately without touching the
    //      executor or persistence port, framed by RunStarted and TaskFinished
    def "returns Completed immediately from a PipelineEnd position"() {
        given: 'a state parked at the pipeline end'
        def state = new TaskState(new Position.PipelineEnd(), 0, [], ExecutorUsage.none())

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stage('build', 3)), CONTEXT, state, WORKSPACE, ports())

        then: 'the outcome is Completed carrying the entry state'
        outcome instanceof TaskOutcome.Completed
        outcome.finalState().is(state)

        and: 'no execution or persistence port was reached'
        executor.requests.isEmpty()
        persistence.entries.isEmpty()

        and: 'exactly the RunStarted then TaskFinished bookends fired'
        listener.events.size() == 2
        listener.events[0] instanceof EngineEvent.RunStarted
        listener.events[1] instanceof EngineEvent.TaskFinished
    }

    // FR9: an AtStage position naming a stage absent from the pipeline escalates as a
    //      PipelineMismatch before any execution or persistence port call
    def "escalates a stale AtStage position as PipelineMismatch"() {
        given: 'a state at a stage the pipeline no longer declares'
        def state = TaskState.atStageStart('gone')

        when: 'the run is driven against a pipeline without that stage'
        def outcome = new Engine().run(pipeline(stage('build', 3)), CONTEXT, state, WORKSPACE, ports())

        then: 'the outcome is Escalated(PipelineMismatch) naming the stale stage'
        outcome instanceof TaskOutcome.Escalated
        outcome.finalState().is(state)
        outcome.report() instanceof EscalationReport.PipelineMismatch
        outcome.report().staleStage() == 'gone'

        and: 'no execution or persistence port was reached'
        executor.requests.isEmpty()
        persistence.entries.isEmpty()

        and: 'the RunStarted and TaskFinished bookends still fired'
        listener.events.size() == 2
        listener.events[0] instanceof EngineEvent.RunStarted
        listener.events[1] instanceof EngineEvent.TaskFinished
    }

    // FR5: attemptsUsed already at the resolved limit on entry escalates as
    //      AttemptsExhausted carrying that limit, before any port work
    def "escalates AttemptsExhausted when attemptsUsed is at the limit on entry"() {
        given: 'a state at a 2-attempt stage with both attempts already burned'
        def state = new TaskState(new Position.AtStage('build'), 2, [], ExecutorUsage.none())

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stage('build', 2)), CONTEXT, state, WORKSPACE, ports())

        then: 'the outcome is Escalated(AttemptsExhausted) carrying the resolved limit'
        outcome instanceof TaskOutcome.Escalated
        outcome.finalState().is(state)
        outcome.report() instanceof EscalationReport.AttemptsExhausted
        outcome.report().limit() == 2

        and: 'no execution or persistence port was reached'
        executor.requests.isEmpty()
        persistence.entries.isEmpty()

        and: 'the RunStarted and TaskFinished bookends still fired'
        listener.events.size() == 2
        listener.events[0] instanceof EngineEvent.RunStarted
        listener.events[1] instanceof EngineEvent.TaskFinished
    }

    // FR12: RunStarted carries the entry position and attemptsUsed; TaskFinished carries
    //       the terminal outcome — payloads verified end to end
    def "emits RunStarted with the entry position and attemptsUsed and TaskFinished with the outcome"() {
        given: 'a state at the limit so the run terminates in pre-flight'
        def state = new TaskState(new Position.AtStage('build'), 2, [], ExecutorUsage.none())

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stage('build', 2)), CONTEXT, state, WORKSPACE, ports())

        then: 'RunStarted carries the task id, entry position and entry attemptsUsed'
        def started = listener.events[0] as EngineEvent.RunStarted
        started.taskId() == 'TASK-1'
        started.position() == new Position.AtStage('build')
        started.attemptsUsed() == 2

        and: 'TaskFinished carries the task id and the very outcome returned'
        def finished = listener.events[1] as EngineEvent.TaskFinished
        finished.taskId() == 'TASK-1'
        finished.outcome().is(outcome)
    }
}
