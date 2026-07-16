package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.StatusViewSupport
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode

/**
 * Status reconstruction of a single-stage retry run, task 6.3 — the NFR-O2 sufficiency
 * proof for one AUTO stage. Driven end to end through {@code Engine.run} with a recording
 * listener, this spec proves a consumer can rebuild the whole status view — position,
 * attempt counters, per-check verdicts and aggregate metrics — purely from the recorded
 * {@link EngineEvent} stream (via {@code StatusViewSupport.reconstruct}), and that the
 * rebuild equals the run's final state, with each round's events sharing one consistent
 * key. Implements FR12, NFR-O2, UX2 of add-stage-engine.
 */
class StatusReconstructionSpec extends StatusReconstructionSpecBase {

    // NFR-O2: a single AUTO stage with one check that Fails-then-Passes (a real retry) —
    //     the status view rebuilt from the event stream alone equals the run's final state:
    //     position, attemptsUsed, per-round check verdicts and executor usage all match.
    def "reconstructs the full status view of a retry run from the event stream alone"() {
        given: 'a single AUTO stage whose check fails once (burns an attempt) then passes'
        def stageDef = stage('build', AdvancementMode.AUTO, [builtin('files_exist')])
        builtinRunner.scripted << fail('nope')
        builtinRunner.scripted << new Verdict.Pass()
        def usage0 = usage(120, 34)
        def usage1 = usage(90, 12)
        executor.scripted << completed('build', 0, usage0)
        executor.scripted << completed('build', 1, usage1)

        when: 'the run is driven end to end'
        def outcome = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        and: 'a downstream consumer rebuilds the status view from the recorded events alone'
        def view = StatusViewSupport.reconstruct(listener.events)
        def truth = outcome.finalState()

        then: 'the run completed after the retry — the ground truth to rebuild against'
        outcome instanceof TaskOutcome.Completed

        and: 'NFR-O2: reconstructed position equals the final state position (pipeline end)'
        view.position == truth.position()
        truth.position() instanceof Position.PipelineEnd

        and: 'NFR-O2: the number of rebuilt rounds equals the executed rounds (two: fail then pass)'
        view.rounds == 2

        and: 'NFR-O2: the terminal from TaskFinished equals the returned outcome'
        view.terminalOutcome == outcome
        view.terminalOutcome.finalState() == truth

        and: 'NFR-O2: reconstructed live attemptsUsed equals the final state (reset to 0 by the completing advance)'
        view.attemptsUsed == truth.attemptsUsed()
        view.attemptsUsed == 0

        and: 'NFR-O2: the retry burn stays visible in the stream — the last stage state before advancement burned one'
        view.lastStageAttemptsUsed == 1

        and: 'NFR-O2: per-round check verdicts rebuilt from CheckFinished match — Fail then Pass'
        view.roundCheckResults[0]*.verdict() == [fail('nope')]
        view.roundCheckResults[1]*.verdict() == [new Verdict.Pass()]

        and: 'NFR-O2: per-round executor usage rebuilt from ExecutionFinished matches what was fed'
        view.roundUsages == [usage0, usage1]

        and: 'UX2: each round\'s events share one key that threads the round number consistently'
        view.roundKeys == [
            new AttemptKey('TASK-1', 'build', 0),
            new AttemptKey('TASK-1', 'build', 1)
        ]
        view.roundKeys[0].attempt() == 0
        view.roundKeys[1].attempt() == 1
    }

    // UX2: the (taskId, stage, attempt) key threads consistently — round N's CheckFinished,
    //     ExecutionFinished and AttemptFinished all share one key, and that key's attempt
    //     equals the round number recorded in AttemptFinished.newState().attempts()[N].
    def "the shared key of round N matches the round number in that round's new state history"() {
        given: 'a single AUTO stage that fails once then passes — two keyed rounds'
        def stageDef = stage('build', AdvancementMode.AUTO, [builtin('files_exist')])
        builtinRunner.scripted << fail('nope')
        builtinRunner.scripted << new Verdict.Pass()
        executor.scripted << completed('build', 0, ExecutorUsage.none())
        executor.scripted << completed('build', 1, ExecutorUsage.none())

        when: 'the run is driven'
        new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'for every AttemptFinished the check/exec/finished events of that round share ONE key'
        def finished = listener.events.findAll { it instanceof EngineEvent.AttemptFinished }
        finished.eachWithIndex { fin, roundIndex ->
            def key = (fin as EngineEvent.AttemptFinished).key()

            // the ExecutionFinished of this round carries the same key
            def execKeys = listener.events.findAll {
                it instanceof EngineEvent.ExecutionFinished
            }.collect { (it as EngineEvent.ExecutionFinished).key() }
            assert execKeys.count { it == key } == 1

            // that round's newState records the round at index N with round number == key.attempt
            def newState = (fin as EngineEvent.AttemptFinished).newState()
            assert newState.attempts()[roundIndex].round() == key.attempt()
        }
    }
}
