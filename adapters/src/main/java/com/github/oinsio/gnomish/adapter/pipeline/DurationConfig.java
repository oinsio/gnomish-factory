package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Shared duration-string parsing for the config mappers (task 5.1 of
 * add-claim-heartbeat): the {@code external} check timings
 * ({@link VerifyCheckMapper}) and the tracker heartbeat interval
 * ({@link PipelineMapper}). A well-formed string yields its {@link Duration}; a
 * malformed one is a located {@link ConfigError} appended to the caller's error
 * accumulator (so the mapper discards the whole definition and the loader
 * aggregates it — task 6.5), with the inert {@link Duration#ZERO} returned as
 * the never-observed fallback.
 *
 * <p>Supported forms are the short suffix notation (e.g. {@code 30s}, {@code
 * 15m}, {@code 2h}, {@code 500ms}) and ISO-8601 ({@code PT1H30M}); the short
 * forms are normalized to ISO-8601 before parsing.
 *
 * <p>Implements FR11 of load-pipeline-config, FR3 of add-claim-heartbeat.
 */
final class DurationConfig {

    private DurationConfig() {}

    /**
     * Parses one raw duration string. A well-formed string yields its
     * {@link Duration}. Otherwise, the result is {@link Duration#ZERO}: for an
     * absent ({@code null}) field this is the observable value carried into the
     * domain, which a domain rule (e.g. {@code StageSanityRule},
     * {@code TrackerConfigRule}) then flags as non-positive; for a malformed
     * string it is an inert fallback whose value is never observed — a located
     * error is appended, so the mapper discards the whole definition. Both
     * non-parse paths share the single {@code ZERO} return so the absent-field
     * test fully pins this method's fallback.
     *
     * @param file the offending file, stamped into any located error
     * @param where the field locator within the file, stamped into any error
     * @param raw the raw duration string, or {@code null} when the field is absent
     * @param errors the caller's mutable error accumulator (aggregation contract)
     */
    static Duration parse(String file, String where, @Nullable String raw, List<ConfigError> errors) {
        if (raw != null) {
            try {
                return Duration.parse(toIso8601(raw));
            } catch (DateTimeParseException e) {
                errors.add(new ConfigError(
                        file, where, "malformed duration '%s'; use e.g. '30s', '15m', '2h'".formatted(raw)));
            }
        }
        return Duration.ZERO;
    }

    /**
     * Normalizes a short duration form to ISO-8601 for {@link Duration#parse}: a
     * bare {@code <number><unit>} (units {@code ms}, {@code s}, {@code m},
     * {@code h}, {@code d}) is rewritten; anything already starting with
     * {@code P}/{@code p} (ISO-8601) is passed through untouched, as is anything
     * that does not match — {@link Duration#parse} then rejects it as malformed.
     */
    private static String toIso8601(String raw) {
        String trimmed = raw.trim();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("^(\\d+)(ms|s|m|h|d)$").matcher(trimmed);
        if (!matcher.matches()) {
            return trimmed;
        }
        String number = matcher.group(1);
        return switch (matcher.group(2)) {
            case "ms" ->
                "PT%sS"
                        .formatted(new java.math.BigDecimal(number)
                                .movePointLeft(3)
                                .toPlainString());
            case "s" -> "PT%sS".formatted(number);
            case "m" -> "PT%sM".formatted(number);
            case "h" -> "PT%sH".formatted(number);
            default -> "P%sD".formatted(number);
        };
    }
}
