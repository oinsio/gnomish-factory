package com.github.oinsio.gnomish.domain.pipeline;

import java.util.Map;

/**
 * The core {@code tracker} section keys carried on {@link PipelineDefinition}
 * (design D5): the adapter discriminator, the abort-fuse threshold shared by
 * all instances, and the raw adapter-owned subsection matching {@code type}.
 * Present exactly when {@code config.yaml} declares a {@code tracker}
 * section; absent (no {@link PipelineDefinition#tracker()}) means tracker
 * subcommands are unavailable and {@code run} is unaffected (FR17).
 *
 * <p>The adapter-owned subsection (e.g. {@code github:}) is validated at the
 * seam by the adapter itself (task 3.2) before ever reaching this record — by
 * construction time its shape is already trustworthy. It is carried through
 * here (task 5.14) because downstream consumers need it: short-ref expansion
 * (FR9, {@link com.github.oinsio.gnomish.app.TrackerAdapterFactory#expandRef})
 * needs {@code api-url}/{@code repo} to mint a canonical id, and real adapter
 * construction (task 5.15) needs the same keys plus {@code labels} to build a
 * live {@code Tracker}. Like the rest of the domain model, it is inert data:
 * an unknown {@code type} or a missing/mismatched subsection is a located
 * {@link ConfigError} reported by the seam validator, not a constructor
 * exception (design D3).
 *
 * <p>Implements FR9, FR17 of add-tracker-port.
 *
 * @param type the adapter discriminator (e.g. {@code github}); never {@code
 *     null} when the section is present — an absent wire value is a located
 *     error at the seam (task 3.2), not represented here
 * @param abortThreshold the resolved core abort-fuse threshold; defaults to 3
 *     when the section is present but the key is omitted (FR17)
 * @param subsection the raw, already-schema-validated subsection matching
 *     {@code type} (e.g. {@code tracker.github}'s {@code api-url}/{@code
 *     repo}/{@code labels} keys); empty when absent
 */
public record TrackerConfig(String type, int abortThreshold, Map<String, Object> subsection) {

    public TrackerConfig {
        subsection = subsection == null ? Map.of() : Map.copyOf(subsection);
    }

    /**
     * Convenience constructor for callers that never need the adapter subsection
     * (most existing tests predating this field) — {@link #subsection()}
     * defaults to an empty map.
     *
     * <p>Implements FR17 of add-tracker-port.
     */
    public TrackerConfig(String type, int abortThreshold) {
        this(type, abortThreshold, Map.of());
    }
}
