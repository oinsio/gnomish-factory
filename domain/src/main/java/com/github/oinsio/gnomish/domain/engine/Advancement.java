package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import org.jspecify.annotations.Nullable;

/**
 * The pipeline-order lookups the {@link Engine} uses to advance a passing stage (FR8): given
 * the current stage, find the stage that follows it in the pipeline's declared order, and turn
 * that "next stage or none" into the {@link Position} a manual pause parks the task at. Kept
 * out of {@link Engine} so the driver loop stays small and under the file-size cap; a
 * stateless bag of static helpers, holding nothing.
 *
 * <p>Implements FR8 of add-stage-engine.
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
     * The {@link Position} a manual pause advances the task to: {@link Position.AtStage} the
     * {@code next} stage when one follows, else the explicit {@link Position.PipelineEnd} — a
     * pause on the last stage parks past the pipeline (FR8).
     *
     * @param next the stage that follows the paused one, or {@code null} when it was the last
     * @return the position the paused task advances to; never null
     */
    static Position nextPosition(@Nullable StageDefinition next) {
        return next == null ? new Position.PipelineEnd() : new Position.AtStage(next.name());
    }
}
