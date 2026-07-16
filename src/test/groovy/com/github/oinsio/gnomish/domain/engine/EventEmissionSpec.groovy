package com.github.oinsio.gnomish.domain.engine

/**
 * Whole-run event emission — choreography and keys, task 6.1: driven end to end through
 * {@code Engine.run}, this spec pins the FULL ordered stream of the seven sealed events
 * (FR12) across a quality retry, the shared {@code (taskId, stage, attempt)} key every
 * per-round event carries (UX2), and that {@code ExecutionFinished} carries the round's
 * executor usage. Implements FR12, UX2 of add-stage-engine.
 */
class EventEmissionSpec extends EventEmissionSpecBase {

    // FR12: a single-stage AUTO run with one check that Fails then Passes (a quality retry)
    //       emits the FULL ordered stream — RunStarted, then per round
    //       AttemptStarted, ExecutionFinished, CheckStarted, CheckFinished, AttemptFinished,
    //       then TaskFinished. The exact class sequence and count are asserted.
    def "emits all seven events in choreography order across a quality retry"() {
        given: 'a single AUTO stage with one check that fails once then passes'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        builtinRunner.scripted << fail('nope')
        builtinRunner.scripted << new Verdict.Pass()
        executor.scripted << completed(0)
        executor.scripted << completed(1)

        when: 'the run is driven end to end'
        new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the emitted event classes are exactly the choreographed sequence'
        listener.events*.getClass() == [
            EngineEvent.RunStarted,
            EngineEvent.AttemptStarted,
            EngineEvent.ExecutionFinished,
            EngineEvent.CheckStarted,
            EngineEvent.CheckFinished,
            EngineEvent.AttemptFinished,
            EngineEvent.AttemptStarted,
            EngineEvent.ExecutionFinished,
            EngineEvent.CheckStarted,
            EngineEvent.CheckFinished,
            EngineEvent.AttemptFinished,
            EngineEvent.TaskFinished,
        ]
    }

    // UX2: every per-attempt/per-check event of round N carries the SAME AttemptKey
    //      ('TASK-1','build',N), and AttemptFinished.trace().key() equals that key —
    //      the key logs, events and the trace all share.
    def "every keyed event of a round shares the one AttemptKey"() {
        given: 'a single AUTO stage with one check that fails once then passes'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        builtinRunner.scripted << fail('nope')
        builtinRunner.scripted << new Verdict.Pass()
        executor.scripted << completed(0)
        executor.scripted << completed(1)

        when: 'the run is driven'
        new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the keyed events of round 0 all carry AttemptKey(TASK-1, build, 0)'
        def keyed = listener.events.findAll { !(it instanceof EngineEvent.RunStarted || it instanceof EngineEvent.TaskFinished) }
        def round0 = keyed[0..4]
        round0*.key().every { it == new AttemptKey('TASK-1', 'build', 0) }

        and: 'the keyed events of round 1 all carry AttemptKey(TASK-1, build, 1)'
        def round1 = keyed[5..9]
        round1*.key().every { it == new AttemptKey('TASK-1', 'build', 1) }

        and: 'each round AttemptFinished.trace() header key equals its round key — trace shares the key'
        def finished = keyed.findAll { it instanceof EngineEvent.AttemptFinished }
        (finished[0] as EngineEvent.AttemptFinished).trace().key() == new AttemptKey('TASK-1', 'build', 0)
        (finished[1] as EngineEvent.AttemptFinished).trace().key() == new AttemptKey('TASK-1', 'build', 1)
    }

    // FR12: ExecutionFinished carries the executed round's ExecutorUsage — a non-none() usage
    //       fed to the executor lands on the emitted event unchanged.
    def "ExecutionFinished carries the round's executor usage"() {
        given: 'a passing single-check stage whose executor reports a non-none usage'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        def fed = usage(120, 34)
        builtinRunner.scripted << new Verdict.Pass()
        executor.scripted << completed(0, fed)

        when: 'the run is driven'
        new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the single ExecutionFinished carries the fed usage and the round key'
        def exec = listener.events.findAll { it instanceof EngineEvent.ExecutionFinished }
        exec.size() == 1
        (exec[0] as EngineEvent.ExecutionFinished).usage() == fed
        (exec[0] as EngineEvent.ExecutionFinished).key() == new AttemptKey('TASK-1', 'build', 0)
    }
}
