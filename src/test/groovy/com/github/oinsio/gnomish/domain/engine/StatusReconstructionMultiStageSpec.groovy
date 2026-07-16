package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.StatusViewSupport
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode

/**
 * Status reconstruction across a multi-stage run, task 6.3 — the NFR-O2 sufficiency proof
 * for auto-advance between stages. Rebuilt from the recorded {@link EngineEvent} stream
 * alone (via {@code StatusViewSupport.reconstruct}), the reconstruction tracks the position
 * change and the per-stage history reset: each {@code AttemptFinished.newState()} carries its
 * own position and a history scoped to its current stage. Implements FR12, FR14, NFR-O2, UX2
 * of add-stage-engine.
 */
class StatusReconstructionMultiStageSpec extends StatusReconstructionSpecBase {

    // NFR-O2 (multi-stage): auto-advance across two stages resets history per stage — the
    //     reconstruction tracks the position change and the per-stage history reset from the
    //     event stream alone. Each AttemptFinished.newState() carries its own position and a
    //     history scoped to its current stage.
    def "reconstructs position changes and per-stage history resets across a multi-stage run"() {
        given: 'two AUTO stages, each passing after one round, so history resets between them'
        def stage1 = stage('build', AdvancementMode.AUTO, [builtin('build_check')])
        def stage2 = stage('test', AdvancementMode.AUTO, [builtin('test_check')])
        builtinRunner.scripted << new Verdict.Pass()
        builtinRunner.scripted << new Verdict.Pass()
        executor.scripted << completed('build', 0, usage(10, 1))
        executor.scripted << completed('test', 0, usage(20, 2))

        when: 'the run is driven through both stages'
        def outcome = new Engine().run(pipeline(stage1, stage2), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        and: 'the status view is rebuilt from the events alone'
        def view = StatusViewSupport.reconstruct(listener.events)
        def truth = outcome.finalState()

        then: 'the run completed at the pipeline end — the ground truth'
        outcome instanceof TaskOutcome.Completed
        view.position == truth.position()
        truth.position() instanceof Position.PipelineEnd

        and: 'NFR-O2: two rounds are rebuilt, one per stage, each keyed to its own stage'
        view.rounds == 2
        view.roundKeys == [
            new AttemptKey('TASK-1', 'build', 0),
            new AttemptKey('TASK-1', 'test', 0)
        ]

        and: 'FR14: the per-round new states show the position advancing build -> test -> end'
        // AttemptFinished carries the recorded state BEFORE advancement, so the build round\'s
        // state is still AtStage(build) and the test round\'s state is AtStage(test); the final
        // state is parked past the last stage at PipelineEnd (advancement happens after the loop).
        def finished = listener.events.findAll { it instanceof EngineEvent.AttemptFinished }
        def state0 = (finished[0] as EngineEvent.AttemptFinished).newState()
        def state1 = (finished[1] as EngineEvent.AttemptFinished).newState()
        state0.position() == new Position.AtStage('build')
        state1.position() == new Position.AtStage('test')
        truth.position() instanceof Position.PipelineEnd

        and: 'FR14: the history reset is visible per event — each stage\'s state history is scoped to that stage alone'
        // two rounds executed in total, but the test round\'s state history holds ONLY the test
        // round (round 0) — the build history did not carry over: it was reset on advancement.
        state0.attempts()*.round() == [0]
        state1.attempts()*.round() == [0]
        truth.attempts().isEmpty()

        and: 'NFR-O2: each stage\'s check verdict and executor usage rebuild independently'
        view.roundCheckResults[0]*.verdict() == [new Verdict.Pass()]
        view.roundCheckResults[1]*.verdict() == [new Verdict.Pass()]
        view.roundUsages == [usage(10, 1), usage(20, 2)]
    }
}
