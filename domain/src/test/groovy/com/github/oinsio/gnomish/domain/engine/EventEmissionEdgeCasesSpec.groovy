package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * Whole-run event emission — non-standard rounds, task 6.1: driven end to end through
 * {@code Engine.run}, this spec pins the event stream for the runs that deviate from the
 * standard per-round choreography (FR12) — a pre-flight run parked at PipelineEnd emits only
 * the two bookends, a DecisionNeeded round emits ExecutionFinished but no check events, and a
 * round whose executor throws still emits its ExecutionFinished, as {@code failed/infrastructure}
 * (NFR-O2 of define-executor-contract: every executor run has its round line). Implements FR12 of
 * add-stage-engine; NFR-O2 of define-executor-contract.
 */
class EventEmissionEdgeCasesSpec extends EventEmissionSpecBase {

    // FR12 (pre-flight bookends): a run starting at PipelineEnd emits exactly RunStarted then
    //      TaskFinished and nothing else — no ExecutionFinished, no per-round events.
    def "a PipelineEnd run emits only the two bookends"() {
        given: 'a state already parked at pipeline end'
        def state = new TaskState(new Position.PipelineEnd(), 0, [], UsageSnapshot.EMPTY, ExecutorVersions.none())

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
        executor.scripted << ExecutionResult.decisionNeeded(
                UntrustedText.agent('which?'),
                [
                    UntrustedText.agent('a'),
                    UntrustedText.agent('b')
                ],
                ExecutorUsage.none(),
                new ToolTrace(new AttemptKey('TASK-1', 'build', 0), []),
                [])

        when: 'the run is driven'
        new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'ExecutionFinished fired once for the executed round'
        listener.events.findAll {
            it instanceof EngineEvent.ExecutionFinished
        }.size() == 1

        and: 'no check events fired — no verify chain ran'
        listener.events.findAll {
            it instanceof EngineEvent.CheckStarted
        }.isEmpty()
        listener.events.findAll {
            it instanceof EngineEvent.CheckFinished
        }.isEmpty()
    }

    // FR12; NFR-O2 of define-executor-contract: a round whose executor throws records nothing, yet
    //       it was an executor run, so it emits ExecutionFinished as failed/infrastructure — and no
    //       AttemptFinished, since nothing was recorded.
    def "a round whose executor throws emits ExecutionFinished as failed infrastructure and no AttemptFinished"() {
        given: 'a stage whose executor port throws'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        executor.toThrow = new RuntimeException('boom')

        when: 'the run is driven'
        new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'one ExecutionFinished names the failed/infrastructure status'
        def finished = listener.events.findAll {
            it instanceof EngineEvent.ExecutionFinished
        }
        finished.size() == 1
        def status = (finished[0] as EngineEvent.ExecutionFinished).status()
        status instanceof ExecutionResult.Failed
        (status as ExecutionResult.Failed).failureClass() == ExecutionResult.FailureClass.INFRASTRUCTURE
        listener.events.findAll {
            it instanceof EngineEvent.AttemptFinished
        }.isEmpty()

        and: 'the run bookends still framed the run'
        listener.events.first() instanceof EngineEvent.RunStarted
        listener.events.last() instanceof EngineEvent.TaskFinished
    }
}
