package com.github.oinsio.gnomish.domain.engine

/**
 * Whole-run event emission — non-standard rounds, task 6.1: driven end to end through
 * {@code Engine.run}, this spec pins the event stream for the runs that deviate from the
 * standard per-round choreography (FR12) — a pre-flight run parked at PipelineEnd emits only
 * the two bookends, a DecisionNeeded round emits ExecutionFinished but no check events, and a
 * CannotExecute round (executor throws) emits no ExecutionFinished. Implements FR12 of
 * add-stage-engine.
 */
class EventEmissionEdgeCasesSpec extends EventEmissionSpecBase {

    // FR12 (pre-flight bookends): a run starting at PipelineEnd emits exactly RunStarted then
    //      TaskFinished and nothing else — no ExecutionFinished, no per-round events.
    def "a PipelineEnd run emits only the two bookends"() {
        given: 'a state already parked at pipeline end'
        def state = new TaskState(new Position.PipelineEnd(), 0, [], ExecutorUsage.none())

        when: 'the run is driven'
        new Engine().run(pipeline(stage('build', 5, [builtin('files_exist')])), CONTEXT, state, WORKSPACE, ports())

        then: 'exactly RunStarted then TaskFinished fired'
        listener.events*.getClass() == [
            EngineEvent.RunStarted,
            EngineEvent.TaskFinished
        ]
    }

    // FR12: a DecisionNeeded round emits ExecutionFinished (execution finished) but NO
    //       CheckStarted/CheckFinished — no verify chain ran because the executor asked a human.
    def "a DecisionNeeded round emits ExecutionFinished but no check events"() {
        given: 'a stage whose executor asks a human instead of completing'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        executor.scripted << new ExecutionResult.DecisionNeeded(
                'which?', ['a', 'b'], ExecutorUsage.none(), new ToolTrace(new AttemptKey('TASK-1', 'build', 0), []))

        when: 'the run is driven'
        new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'ExecutionFinished fired once for the executed round'
        listener.events.findAll { it instanceof EngineEvent.ExecutionFinished }.size() == 1

        and: 'no check events fired — no verify chain ran'
        listener.events.findAll { it instanceof EngineEvent.CheckStarted }.isEmpty()
        listener.events.findAll { it instanceof EngineEvent.CheckFinished }.isEmpty()
    }

    // FR12: a CannotExecute round (executor throws) emits NO ExecutionFinished — the execution
    //       never finished; the throw is caught before the event point.
    def "a CannotExecute round emits no ExecutionFinished"() {
        given: 'a stage whose executor port throws'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        executor.toThrow = new RuntimeException('boom')

        when: 'the run is driven'
        new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'no ExecutionFinished was emitted'
        listener.events.findAll { it instanceof EngineEvent.ExecutionFinished }.isEmpty()

        and: 'the run bookends still framed the run'
        listener.events.first() instanceof EngineEvent.RunStarted
        listener.events.last() instanceof EngineEvent.TaskFinished
    }
}
