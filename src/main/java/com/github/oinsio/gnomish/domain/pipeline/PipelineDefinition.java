package com.github.oinsio.gnomish.domain.pipeline;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The typed, immutable model of a target project's {@code .gnomish/} pipeline —
 * the payload of {@link LoadOutcome.Loaded}: the tree-wide schema version
 * declared in {@code config.yaml}, the pipeline-wide autonomy defaults from
 * {@code config.yaml}, the stages in exactly the {@code pipeline.yaml}
 * declaration order (design D4: order is declared, never derived from the
 * artifact DAG), and the optional {@code tracker} core config (FR17 of
 * add-tracker-port).
 *
 * <p>The record is inert, immutable data: the stage list is defensively copied
 * and unmodifiable, and no semantic rule is enforced here — schema-version
 * support (FR9, task 4.1) and non-empty/unique stage order (FR3, task 4.2) are
 * the pure validators' concern (design D6), reported as located
 * {@link ConfigError}s; a throwing constructor would destroy an invalid value
 * before a validator could see and report it.
 *
 * <p>Implements FR1 of load-pipeline-config; FR17 of add-tracker-port (the
 * {@code tracker} field).
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
 * @param tracker the core {@code tracker} section config, or {@code null}
 *     when {@code config.yaml} declares no {@code tracker} section at all
 *     (FR17 of add-tracker-port)
 */
public record PipelineDefinition(
        String schemaVersion,
        AutonomyLimits defaultLimits,
        List<StageDefinition> stages,
        @Nullable TrackerConfig tracker) {

    public PipelineDefinition {
        stages = List.copyOf(stages);
    }

    /**
     * Convenience constructor for callers that never declare a {@code tracker}
     * section — every call site predating add-tracker-port keeps compiling
     * unchanged, with {@link #tracker()} defaulting to {@code null}.
     *
     * <p>Implements FR17 of add-tracker-port.
     */
    public PipelineDefinition(String schemaVersion, AutonomyLimits defaultLimits, List<StageDefinition> stages) {
        this(schemaVersion, defaultLimits, stages, null);
    }
}
