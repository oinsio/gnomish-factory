package com.github.oinsio.gnomish.domain.pipeline;

import java.util.List;

/**
 * The typed, immutable model of a target project's {@code .gnomish/} pipeline —
 * the payload of {@link LoadOutcome.Loaded}: the tree-wide schema version
 * declared in {@code config.yaml}, the pipeline-wide autonomy defaults from
 * {@code config.yaml}, and the stages in exactly the {@code pipeline.yaml}
 * declaration order (design D4: order is declared, never derived from the
 * artifact DAG).
 *
 * <p>The record is inert, immutable data: the stage list is defensively copied
 * and unmodifiable, and no semantic rule is enforced here — schema-version
 * support (FR9, task 4.1) and non-empty/unique stage order (FR3, task 4.2) are
 * the pure validators' concern (design D6), reported as located
 * {@link ConfigError}s; a throwing constructor would destroy an invalid value
 * before a validator could see and report it.
 *
 * <p>Implements FR1 of load-pipeline-config.
 *
 * @param schemaVersion the version declared in {@code config.yaml} for the
 *     whole {@code .gnomish/} tree (FR9); supported-ness validated by task
 *     4.1, not here
 * @param defaultLimits the pipeline-wide autonomy defaults from
 *     {@code config.yaml} — the base of the FR7 default+override resolution;
 *     each stage's own {@link StageDefinition#limits()} is already resolved
 * @param stages the stages in exactly the {@code pipeline.yaml} declaration
 *     order (FR3); non-emptiness and name uniqueness validated by task 4.2,
 *     not here
 */
public record PipelineDefinition(String schemaVersion, AutonomyLimits defaultLimits, List<StageDefinition> stages) {

    public PipelineDefinition {
        stages = List.copyOf(stages);
    }
}
