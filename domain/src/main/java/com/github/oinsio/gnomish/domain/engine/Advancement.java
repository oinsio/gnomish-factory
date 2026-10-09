package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import org.jspecify.annotations.Nullable;

/**
 * The pipeline-order lookups the {@link Engine} uses to advance a passing stage (FR8): given
 * the current stage, find the stage that follows it in the pipeline's declared order, and decide
 * the {@link Position} a pass of that stage leaves the task at. Kept out of {@link Engine} so the
 * driver loop stays small and under the file-size cap; a stateless bag of static helpers, holding
 * nothing.
 *
 * <p>{@link #positionAfter} is the single owner of "what a pass leaves as position" (design D7 of
 * make-checkpoint-gate-durable): the round commit records it, and the approval of a gate reaches
 * its {@code AUTO} branch, {@link #afterGate}, only through {@link TaskState#approveGate}.
 *
 * <p>Implements FR8 of add-stage-engine; FR1, FR3 of make-checkpoint-gate-durable.
 */
final class Advancement {

    private Advancement() {}

    /**
     * Returns the stage declared immediately after {@code current} in the pipeline's order, or
     * {@code null} when {@code current} is the last stage — the signal the {@link Engine} turns
     * into a {@link Position.PipelineEnd} (design D4). Matches by name, so it resolves against
     * the pipeline the run is driving even if {@code current} is a different instance (FR8).
     *
     * @param definition the pipeline whose declared order is walked; never null
     * @param current the stage whose successor is sought; never null
     * @return the following stage, or {@code null} when {@code current} is last
     */
    @Nullable
    static StageDefinition nextStage(PipelineDefinition definition, StageDefinition current) {
        var stages = definition.stages();
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).name().equals(current.name()) && i + 1 < stages.size()) {
                return stages.get(i + 1);
            }
        }
        return null;
    }

    /**
     * Where a pass of {@code stage} leaves the task, decided by the stage's advancement mode. A
     * {@code MANUAL} stage leaves the task <em>at the gate</em>, {@link Position.AwaitingApproval}
     * naming the stage that passed, never past it: the position is the gate, so no tip ever says
     * "continue" before the approval that allows it is recorded (FR1 of
     * make-checkpoint-gate-durable, design D1, which reversed the earlier mode-blind rule). An
     * {@code AUTO} stage leaves it at the following stage, or at the explicit {@link
     * Position.PipelineEnd} past the last one ({@link #afterGate}). The {@link StageAttemptLoop}
     * asks this to decide what a passing round's own commit records (FR4 of
     * harden-task-branch-contract).
     *
     * @param definition the pipeline whose declared order is walked; never null
     * @param stage the stage that just passed; never null
     * @return the position the pass leaves the task at; never null
     */
    static Position positionAfter(PipelineDefinition definition, StageDefinition stage) {
        return switch (stage.advancement()) {
            case MANUAL -> new Position.AwaitingApproval(stage.name());
            case AUTO -> afterGate(definition, stage);
        };
    }

    /**
     * The position that follows {@code stage} in the pipeline's declared order: {@link
     * Position.AtStage} the next stage, or the explicit {@link Position.PipelineEnd} when {@code
     * stage} is last (FR8). This is the {@code AUTO} branch of {@link #positionAfter} and the
     * position an approved gate moves to; the approval reaches it only through {@link
     * TaskState#approveGate} (design D2, D7 of make-checkpoint-gate-durable). The stage's own
     * advancement mode does not enter here — that decision is {@link #positionAfter}'s.
     *
     * @param definition the pipeline whose declared order is walked; never null
     * @param stage the stage the task moves past; never null
     * @return the following position; never null and never a gate
     */
    static Position afterGate(PipelineDefinition definition, StageDefinition stage) {
        var next = nextStage(definition, stage);
        return next == null ? new Position.PipelineEnd() : new Position.AtStage(next.name());
    }
}
