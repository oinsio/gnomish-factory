package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;

/**
 * Resolves the starting stage's attempt limit for the initial {@code StatusSnapshotHolder} value.
 * Extracted from {@link RunAssembly} for file size.
 */
final class AttemptLimitResolver {

    private AttemptLimitResolver() {}

    /**
     * Returns {@code StageDefinition.limits()} for {@code position}'s named stage when found, else the
     * pipeline default (a fallback that only matters if {@code position} names a stage absent from
     * {@code definition}).
     */
    static int resolve(PipelineDefinition definition, Position position) {
        if (!(position instanceof Position.AtStage(String stageName))) {
            return definition.defaultLimits().attemptLimit();
        }
        StageDefinition stage = definition.findStage(stageName);
        return stage != null
                ? stage.limits().attemptLimit()
                : definition.defaultLimits().attemptLimit();
    }
}
