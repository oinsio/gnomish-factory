package com.github.oinsio.gnomish.domain.engine;

/**
 * The sealed result the {@link StageAttemptLoop} hands back for one stage, so the
 * {@link Engine} — which alone knows the pipeline and can find the next stage — drives
 * stage-to-stage advancement (FR8). The loop resolves one stage to either a {@link Passed}
 * — the stage's verification passed and its passing round is already recorded and persisted,
 * the position still {@link Position.AtStage} the current stage — or a {@link Terminal} —
 * any outcome that ends the run within the stage (an escalation or an abort), which the
 * engine returns verbatim.
 *
 * <p>Splitting "the stage passed" from "the run ended" keeps advancement — inherently
 * cross-stage, since it must look the next stage up in the pipeline — out of the loop, which
 * knows only one {@link com.github.oinsio.gnomish.domain.pipeline.StageDefinition} (design D4).
 *
 * <p>Implements FR8 of add-stage-engine.
 */
sealed interface StageResult permits StageResult.Passed, StageResult.Terminal {

    /**
     * The stage's verification passed: {@code state} is the round-recorded, already-persisted
     * state whose {@link Position} still names the current stage and whose history holds the
     * passing round. The engine applies the stage's advancement mode to it — advancing to the
     * next stage (history reset, FR14), completing at the pipeline end, or pausing (FR8).
     *
     * @param state the passing round's recorded and persisted state; never null
     */
    record Passed(TaskState state) implements StageResult {}

    /**
     * The stage resolved to a run-ending {@link TaskOutcome} — an escalation
     * ({@code CannotVerify}, {@code DecisionNeeded}, {@code CannotExecute} or
     * {@code AttemptsExhausted}) or an {@code Aborted} — that the engine returns unchanged;
     * no advancement applies.
     *
     * @param outcome the terminal outcome the stage resolved to; never null
     */
    record Terminal(TaskOutcome outcome) implements StageResult {}
}
