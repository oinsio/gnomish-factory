package com.github.oinsio.gnomish.domain.pipeline;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The pure tracker core-key rule (design D6): checks the semantics of the
 * {@code tracker} core keys the loader itself owns — currently the shared
 * {@code abort-threshold}, which the pipeline-config spec fixes as a
 * <em>positive integer</em> (default 3). A declared {@code abort-threshold} of
 * {@code 0} or negative is reported as a located {@link ConfigError} naming
 * {@code config.yaml}, never thrown (design D3) — mirroring how
 * {@link StageSanityRule} flags a non-positive resolved attempt limit.
 *
 * <p>Default contract: {@link PipelineMapper} substitutes 3 only when the key is
 * <em>omitted</em> (the wire value is {@code null}); an explicitly declared
 * value flows through to {@link TrackerConfig#abortThreshold()} unchanged, so a
 * declared non-positive value reaches this rule as-is and is flagged, while an
 * omitted one is already the valid default 3 by the time it gets here.
 *
 * <p>Scope (no validation creep): the adapter-owned subsection is validated at
 * the seam by {@link com.github.oinsio.gnomish.adapter.pipeline.TrackerSeamValidator}
 * (task 3.2), not here; and an absent {@code tracker} section ({@code null})
 * yields no error — the section is optional (FR17).
 *
 * <p>Implements FR17 of add-tracker-port.
 */
public final class TrackerConfigRule {

    private static final String FILE = "config.yaml";
    private static final String WHERE = "tracker.abort-threshold";

    private TrackerConfigRule() {}

    /**
     * Validates the model's tracker core keys: an absent section, or a section
     * with a positive {@code abort-threshold}, yields no errors; a declared
     * non-positive {@code abort-threshold} yields exactly one located
     * {@link ConfigError}.
     *
     * <p>Implements FR17 of add-tracker-port.
     *
     * @param tracker the carried tracker core config, or {@code null} when
     *     {@code config.yaml} declares no {@code tracker} section
     */
    public static List<ConfigError> validate(@Nullable TrackerConfig tracker) {
        if (tracker == null) {
            return List.of();
        }
        int threshold = tracker.abortThreshold();
        if (threshold < 1) {
            return List.of(new ConfigError(
                    FILE,
                    WHERE,
                    "non-positive abort-threshold %d; the threshold must be a positive integer".formatted(threshold)));
        }
        return List.of();
    }
}
