package com.github.oinsio.gnomish.domain.pipeline;

import org.jspecify.annotations.Nullable;

/**
 * The resolved autonomy limits of a pipeline stage — the attempt limit only.
 * Token/money budgets are deliberately not modeled here (NG8); they belong to
 * the ai-provider/executor changes that can give them semantics.
 *
 * <p>The record carries the resolved value <em>without</em> range enforcement:
 * the FR7 "limit &ge; 1" rule is checked by the pure validators as a located
 * {@link ConfigError} (design D6, task 4.4). A throwing constructor would
 * destroy an invalid value before the validator could see and report it.
 *
 * <p>Implements FR7 of load-pipeline-config.
 *
 * @param attemptLimit the resolved stage attempt limit; range-validated
 *     (&ge; 1) by the pure validators, not here
 */
public record AutonomyLimits(int attemptLimit) {

    /**
     * Resolves the effective attempt limit (FR7): the per-stage override takes
     * precedence over the {@code config.yaml} default; the default applies when
     * the stage declares no override.
     *
     * <p>Implements FR7 of load-pipeline-config.
     *
     * @param defaultLimit the pipeline-wide default from {@code config.yaml}
     * @param stageOverride the stage's own limit from its {@code stage.yaml},
     *     or {@code null} when the stage declares none
     */
    public static AutonomyLimits resolve(int defaultLimit, @Nullable Integer stageOverride) {
        return new AutonomyLimits(stageOverride != null ? stageOverride : defaultLimit);
    }
}
