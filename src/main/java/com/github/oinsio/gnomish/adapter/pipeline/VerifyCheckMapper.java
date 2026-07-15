package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Maps a stage's ordered {@code verify} DTO list into the sealed domain
 * {@link VerifyCheck} variants (task 5.3, design D2/D5), preserving check order.
 * The {@code type} discriminator is already valid (task 5.2, FR5), so the switch
 * is total.
 *
 * <p>This mapper owns the one piece of parsing task 5.1 deferred: the
 * {@code external} check's raw {@code interval}/{@code timeout} strings become
 * {@link Duration}s here. A malformed string is a located {@link ConfigError}
 * appended to the caller's error list (so {@link PipelineMapper} aborts the
 * definition and the loader aggregates it — task 6.5); a {@code null} (absent)
 * string maps to {@link Duration#ZERO}, which {@code StageSanityRule} (task 4.4)
 * then flags as non-positive — timing-range sanity stays a domain concern.
 *
 * <p>Supported duration forms are the short suffix notation (e.g. {@code 30s},
 * {@code 15m}, {@code 2h}, {@code 500ms}) and ISO-8601 ({@code PT1H30M}); the
 * short forms are normalized to ISO-8601 before parsing.
 *
 * <p>Opaque {@code params}/{@code settings} maps flow through as defensive
 * plain-JDK copies (FR11, D5a) via {@link PipelineMapper#copySettings}.
 *
 * <p>Implements FR2, FR11, D2, D5, D5a of load-pipeline-config.
 */
final class VerifyCheckMapper {

    private VerifyCheckMapper() {}

    /**
     * Maps every verify DTO in order, appending any malformed-duration error to
     * {@code errors}. When a duration is malformed the produced check still holds
     * {@link Duration#ZERO} for that field, but the accumulated error means
     * {@link PipelineMapper} discards the definition anyway.
     *
     * @param manifest the stage manifest path, stamped into any located error
     * @param checks the ordered verify DTOs, or {@code null} when the stage
     *     declares none
     * @param errors the caller's mutable error accumulator (FR8 aggregation)
     * @return the mapped checks in declaration order; empty when {@code checks}
     *     is {@code null}
     */
    static List<VerifyCheck> mapAll(String manifest, @Nullable List<VerifyCheckDto> checks, List<ConfigError> errors) {
        if (checks == null) {
            return List.of();
        }
        List<VerifyCheck> mapped = new ArrayList<>();
        for (int index = 0; index < checks.size(); index++) {
            mapped.add(mapCheck(manifest, index, checks.get(index), errors));
        }
        return mapped;
    }

    private static VerifyCheck mapCheck(String manifest, int index, VerifyCheckDto dto, List<ConfigError> errors) {
        return switch (dto) {
            case VerifyCheckDto.Builtin builtin ->
                new VerifyCheck.Builtin(
                        PipelineMapper.orEmpty(builtin.name()), PipelineMapper.copySettings(builtin.params()));
            case VerifyCheckDto.Command command -> new VerifyCheck.Command(PipelineMapper.orEmpty(command.command()));
            case VerifyCheckDto.External external -> mapExternal(manifest, index, external, errors);
            case VerifyCheckDto.Judge judge ->
                new VerifyCheck.Judge(
                        PipelineMapper.orEmpty(judge.criteriaFile()),
                        PipelineMapper.orEmpty(judge.model()),
                        PipelineMapper.copySettings(judge.settings()),
                        judge.votes() == null ? 0 : judge.votes());
        };
    }

    private static VerifyCheck mapExternal(
            String manifest, int index, VerifyCheckDto.External external, List<ConfigError> errors) {
        Duration interval = parseDuration(manifest, index, "interval", external.interval(), errors);
        Duration timeout = parseDuration(manifest, index, "timeout", external.timeout(), errors);
        return new VerifyCheck.External(PipelineMapper.orEmpty(external.checkId()), interval, timeout);
    }

    /**
     * Parses one raw timing string. A well-formed string yields its
     * {@link Duration}. Otherwise, the result is {@link Duration#ZERO}: for an
     * absent ({@code null}) field this is the observable value carried into the
     * domain, which {@code StageSanityRule} (task 4.4) then flags as non-positive;
     * for a malformed string it is an inert fallback whose value is never observed
     * — a located error is appended, so {@link PipelineMapper} discards the whole
     * definition. Both non-parse paths share the single {@code ZERO} return so the
     * absent-field test fully pins this method's fallback.
     */
    private static Duration parseDuration(
            String manifest, int index, String field, @Nullable String raw, List<ConfigError> errors) {
        if (raw != null) {
            try {
                return Duration.parse(toIso8601(raw));
            } catch (DateTimeParseException e) {
                errors.add(new ConfigError(
                        manifest,
                        "verify[%d].%s".formatted(index, field),
                        "malformed duration '%s'; use e.g. '30s', '15m', '2h'".formatted(raw)));
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
