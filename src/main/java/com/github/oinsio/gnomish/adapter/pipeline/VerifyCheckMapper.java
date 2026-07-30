package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Maps a stage's ordered {@code verify} DTO list into the sealed domain
 * {@link VerifyCheck} variants (task 5.3, design D2/D5), preserving check order.
 * The {@code type} discriminator is already valid (task 5.2, FR5), so the switch
 * is total.
 *
 * <p>This mapper owns one piece of parsing task 5.1 deferred: the
 * {@code external} check's raw {@code interval}/{@code timeout} strings become
 * {@link Duration}s here via the shared {@link DurationConfig#parse}. A
 * malformed string is a located {@link ConfigError} appended to the caller's
 * error list (so {@link PipelineMapper} aborts the definition and the loader
 * aggregates it — task 6.5); a {@code null} (absent) string maps to
 * {@link Duration#ZERO}, which {@code StageSanityRule} (task 4.4) then flags as
 * non-positive — timing-range sanity stays a domain concern.
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
        Duration interval =
                DurationConfig.parse(manifest, "verify[%d].interval".formatted(index), external.interval(), errors);
        Duration timeout =
                DurationConfig.parse(manifest, "verify[%d].timeout".formatted(index), external.timeout(), errors);
        return new VerifyCheck.External(PipelineMapper.orEmpty(external.checkId()), interval, timeout);
    }
}
