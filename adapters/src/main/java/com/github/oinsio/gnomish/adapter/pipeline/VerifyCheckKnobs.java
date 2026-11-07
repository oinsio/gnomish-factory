package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Parses a verify check's raw enum knobs — {@code verifyIn}, {@code timeout-class} and {@code
 * threshold} — into their domain constants. Each absent value takes its documented default, and
 * an unrecognized one is a located {@link ConfigError} appended to the caller's list, returning
 * the same safe default ({@link DurationConfig#parse}'s convention) since the accumulated error
 * discards the whole definition anyway.
 *
 * <p>Implements FR13 of add-sandbox-core; FR9 of add-external-check-github-actions; FR7 of
 * migrate-verify-to-executor-contract.
 */
final class VerifyCheckKnobs {

    private VerifyCheckKnobs() {}

    /**
     * Parses the raw {@code verifyIn} string (FR13 of add-sandbox-core):
     * {@code null} (absent) defaults to {@link VerifyCheck.VerifyIn#SAME_BOX} —
     * unchanged behavior; {@code "same-box"}/{@code "fresh-box"} map to their
     * enum constants; any other value is a located {@link ConfigError}, still
     * returning the safe {@code SAME_BOX} fallback (mirroring
     * {@link #timeoutClass}) since the accumulated error discards the whole
     * definition anyway.
     */
    static VerifyCheck.VerifyIn verifyIn(String manifest, int index, @Nullable String raw, List<ConfigError> errors) {
        if (raw == null || raw.equals("same-box")) {
            return VerifyCheck.VerifyIn.SAME_BOX;
        }
        if (raw.equals("fresh-box")) {
            return VerifyCheck.VerifyIn.FRESH_BOX;
        }
        errors.add(new ConfigError(
                manifest,
                "verify[%d].verifyIn".formatted(index),
                "unknown verify-in '%s'; use 'same-box' or 'fresh-box'".formatted(raw)));
        return VerifyCheck.VerifyIn.SAME_BOX;
    }

    /**
     * Parses the raw {@code timeout-class} string (FR9): {@code null} (absent)
     * defaults to {@link VerifyCheck.TimeoutClass#QUALITY} — unchanged behavior
     * for checks that declare nothing (D7); {@code "quality"}/{@code
     * "infrastructure"} map to their enum constants; any other value is a
     * located {@link ConfigError}, mirroring {@link DurationConfig#parse}'s
     * convention of still returning a safe fallback ({@code QUALITY}) since the
     * accumulated error means {@link PipelineMapper} discards the whole
     * definition anyway.
     */
    static VerifyCheck.TimeoutClass timeoutClass(
            String manifest, int index, @Nullable String raw, List<ConfigError> errors) {
        if (raw == null || raw.equals("quality")) {
            return VerifyCheck.TimeoutClass.QUALITY;
        }
        if (raw.equals("infrastructure")) {
            return VerifyCheck.TimeoutClass.INFRASTRUCTURE;
        }
        errors.add(new ConfigError(
                manifest,
                "verify[%d].timeout-class".formatted(index),
                "unknown timeout class '%s'; use 'quality' or 'infrastructure'".formatted(raw)));
        return VerifyCheck.TimeoutClass.QUALITY;
    }
    /**
     * Parses the raw {@code threshold} string (FR7 of migrate-verify-to-executor-contract): absent
     * defaults to {@link VerifyCheck.Threshold#ERROR}; {@code error}/{@code warning}/{@code note}
     * map to their constants; any other value is a located {@link ConfigError} with the safe
     * {@code ERROR} fallback, as {@link #timeoutClass} does.
     */
    static VerifyCheck.Threshold threshold(String manifest, int index, @Nullable String raw, List<ConfigError> errors) {
        if (raw == null) {
            return VerifyCheck.Threshold.ERROR;
        }
        for (VerifyCheck.Threshold threshold : VerifyCheck.Threshold.values()) {
            if (threshold.name().toLowerCase(Locale.ROOT).equals(raw)) {
                return threshold;
            }
        }
        errors.add(new ConfigError(
                manifest,
                "verify[%d].threshold".formatted(index),
                "unknown threshold '%s'; use 'error', 'warning' or 'note'".formatted(raw)));
        return VerifyCheck.Threshold.ERROR;
    }
}
