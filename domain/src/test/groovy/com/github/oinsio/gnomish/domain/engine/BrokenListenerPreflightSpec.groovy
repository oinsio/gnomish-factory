package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor

/**
 * Broken-listener resilience on pre-flight runs, task 6.2 — the bookend emits
 * (RunStarted / TaskFinished) are swallowed the same way on a pre-flight run that reaches
 * no stage (FR12). Whether the run completes immediately (PipelineEnd) or escalates before
 * any stage (PipelineMismatch), both bookends still fire and a throwing listener leaves the
 * outcome unchanged.
 *
 * <p>Implements FR12, NFR-O1 of add-stage-engine.
 */
class BrokenListenerPreflightSpec extends BrokenListenerSpecBase {

    // FR12 (pre-flight bookends swallowed): a run starting at PipelineEnd reaches Completed
    //       immediately, emitting only RunStarted and TaskFinished. With throwOnEvent = true
    //       both bookends still fire (recorded) and the outcome is unchanged — the bookend
    //       emits are swallowed too, so even a run that touches no stage is resilient.
    def "a pre-flight PipelineEnd run still fires both bookends despite a throwing listener"() {
        given: 'a state already parked at pipeline end and a listener that throws on every event'
        def executor = new ScriptedExecutor()
        def builtinRunner = new ScriptedBuiltinCheckRunner()
        def persistence = new InMemoryAttemptPersistence()
        def listener = new RecordingEventListener(throwOnEvent: true)
        def state = new TaskState(new Position.PipelineEnd(), 0, [], ExecutorUsage.none())

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stage('build', [builtin('files_exist')])), CONTEXT,
        state, WORKSPACE, portsFor(executor, builtinRunner, persistence, listener))

        then: 'the outcome is Completed — the throwing bookends did not change it'
        outcome instanceof TaskOutcome.Completed

        and: 'both bookends still fired and were recorded before each throw'
        listener.events*.getClass() == [
            EngineEvent.RunStarted,
            EngineEvent.TaskFinished,
        ]

        and: 'no stage was touched — the pre-flight completion invoked no executor or persistence port'
        executor.requests.isEmpty()
        persistence.entries.isEmpty()
    }

    // FR12 (pre-flight escalation bookends swallowed): a state whose position names a stage
    //       absent from the pipeline escalates as PipelineMismatch before any stage runs. Even
    //       this pre-flight escalation emits its two bookends, and a throwing listener leaves
    //       the escalation outcome unchanged.
    def "a pre-flight PipelineMismatch run still fires both bookends despite a throwing listener"() {
        given: 'a state pointing at a stage absent from the pipeline and a throwing listener'
        def executor = new ScriptedExecutor()
        def builtinRunner = new ScriptedBuiltinCheckRunner()
        def persistence = new InMemoryAttemptPersistence()
        def listener = new RecordingEventListener(throwOnEvent: true)
        def state = TaskState.atStageStart('ghost-stage')

        when: 'the run is driven against a pipeline that has no such stage'
        def outcome = new Engine().run(pipeline(stage('build', [builtin('files_exist')])), CONTEXT,
        state, WORKSPACE, portsFor(executor, builtinRunner, persistence, listener))

        then: 'the outcome is Escalated(PipelineMismatch) — unchanged by the throwing listener'
        outcome instanceof TaskOutcome.Escalated
        (outcome as TaskOutcome.Escalated).report() instanceof EscalationReport.PipelineMismatch

        and: 'both bookends still fired despite each throw'
        listener.events*.getClass() == [
            EngineEvent.RunStarted,
            EngineEvent.TaskFinished,
        ]

        and: 'no execution or persistence port was invoked before the mismatch escalation'
        executor.requests.isEmpty()
        persistence.entries.isEmpty()
    }
}
