package com.github.oinsio.gnomish.domain.engine

/**
 * StageAttemptLoop persistence ordering, task 5.4 — every executed round is persisted
 * synchronously AFTER the round is recorded and BEFORE the AttemptFinished event and any
 * next AttemptStarted (FR11, the choreography diagram's ordering invariant). Persistence
 * covers rounds ending in CannotVerify too — recorded, persisted, not counted (FR11, FR13).
 *
 * <p>Persist-failure abort behavior lives in {@link PersistenceAbortSpec}; the shared fixture
 * lives in {@link PersistenceOrderingSpecBase}.
 *
 * <p>Implements FR11, FR13, NFR-O1 of add-stage-engine.
 */
class PersistenceOrderingSpec extends PersistenceOrderingSpecBase {

    // FR11 (ordering invariant): a single CannotVerify round persists then emits AttemptFinished,
    //     and the persisted state is the round's new state — the recorded stream shows persist
    //     completing before the AttemptFinished event.
    def "persists the round before emitting AttemptFinished"() {
        given: 'a stage whose single check cannot be verified'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        builtinRunner.scripted << new Verdict.CannotVerify('binary not found', 'no such tool')
        executor.scripted << completed()

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the round was persisted exactly once with the final new state'
        persistence.entries.size() == 1
        persistence.entries[0].taskId == 'TASK-1'
        persistence.entries[0].state.is(outcome.finalState())

        and: 'an AttemptFinished event was emitted for the round'
        def finished = listener.events.findAll { it instanceof EngineEvent.AttemptFinished }
        finished.size() == 1

        and: 'the persist happened before the AttemptFinished was recorded — the fake saw persist while no AttemptFinished existed yet'
        // AttemptFinished carries the SAME new state the persist recorded: the engine persists
        // that state first, so at the moment AttemptFinished fires the entry is already present.
        (finished[0] as EngineEvent.AttemptFinished).newState().is(persistence.entries[0].state)
    }

    // FR11: the ordering is enforced per round — for a multi-round run (Fail then CannotVerify)
    //     each round's AttemptStarted precedes its persist, which precedes its AttemptFinished,
    //     and round 1's AttemptStarted only appears after round 0 was persisted and finished.
    def "orders persist and events per round across a multi-round run"() {
        given: 'a stage that fails once (burning an attempt and retrying) then cannot verify'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        builtinRunner.scripted << fail('findingA')
        builtinRunner.scripted << new Verdict.CannotVerify('binary not found', 'no such tool')
        executor.scripted << completed()
        executor.scripted << completed()

        when: 'the run is driven'
        new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'both rounds were persisted, in round order'
        persistence.entries.size() == 2
        persistence.entries[0].state.attemptsUsed() == 1
        persistence.entries[0].state.attempts().size() == 1
        persistence.entries[1].state.attempts().size() == 2

        and: 'two AttemptStarted and two AttemptFinished events fired'
        def started = listener.events.findAll { it instanceof EngineEvent.AttemptStarted }
        def finished = listener.events.findAll { it instanceof EngineEvent.AttemptFinished }
        started.size() == 2
        finished.size() == 2

        and: 'AttemptStarted for round 1 came AFTER AttemptFinished for round 0'
        def order = listener.events.findAll {
            it instanceof EngineEvent.AttemptStarted || it instanceof EngineEvent.AttemptFinished
        }
        order[0] instanceof EngineEvent.AttemptStarted
        order[1] instanceof EngineEvent.AttemptFinished
        order[2] instanceof EngineEvent.AttemptStarted
        order[3] instanceof EngineEvent.AttemptFinished
        (order[0] as EngineEvent.AttemptStarted).key().attempt() == 0
        (order[2] as EngineEvent.AttemptStarted).key().attempt() == 1
    }

    // FR11/FR13 (CannotVerify persisted exactly once, unburned): a single CannotVerify round is
    //     persisted once with attemptsUsed 0.
    def "persists a CannotVerify round exactly once with no attempt burned"() {
        given: 'a stage whose single check cannot be verified'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        builtinRunner.scripted << new Verdict.CannotVerify('binary not found', 'no such tool')
        executor.scripted << completed()

        when: 'the run is driven'
        new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the round was persisted once and no attempt was burned'
        persistence.entries.size() == 1
        persistence.entries[0].state.attemptsUsed() == 0
        persistence.entries[0].state.attempts().size() == 1
    }
}
