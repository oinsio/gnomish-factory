package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode;
import com.github.oinsio.gnomish.domain.pipeline.ArtifactInput;
import com.github.oinsio.gnomish.domain.pipeline.ArtifactOutput;
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Maps structurally-valid adapter DTOs into the pure domain
 * {@link PipelineDefinition} (task 5.3, design D2). It runs after
 * {@link StructuralParse}/{@link StructuralValidation} have guaranteed the DTOs'
 * shape (required fields present, enums known, discriminators valid — task 5.2,
 * FR5), so the enum and discriminator switches here are total and never a
 * validation concern.
 *
 * <p>Two mapping contracts settle this task's open questions:
 *
 * <ul>
 *   <li><b>Attempt-limit resolution (FR7).</b> The {@code config.yaml} default is
 *       {@code @Nullable Integer}; {@link AutonomyLimits#resolve(int, Integer)}
 *       needs an {@code int}, so an absent default maps to {@code 0}. A stage
 *       with no override then resolves to {@code 0}, which {@code StageSanityRule}
 *       (task 4.4) flags as a non-positive located error — the mapper never
 *       throws for a missing limit (design D3: validation is data, not an
 *       exception).</li>
 *   <li><b>Null &rarr; blank-string / empty (the 5.2&harr;5.3 boundary).</b> Where
 *       a wire field is {@code null}, the mapper hands the domain a blank string
 *       or an empty collection, so the pure domain rules (tasks 4.x) report the
 *       semantic problem (blank model, blank id, empty order) rather than the
 *       mapper crashing.</li>
 * </ul>
 *
 * <p><b>Duration parsing (FR11, deferred here by task 5.1).</b> The one place the
 * mapper can genuinely fail on structurally-valid input is a malformed
 * {@code external} timing string ({@code interval}/{@code timeout}). Rather than
 * throw, the mapper returns a {@link Result}: on success the mapped
 * {@link PipelineDefinition} with an empty error list; on any malformed duration
 * a {@code null} definition and the full list of located {@link ConfigError}s.
 * This composes into the loader's single-pass aggregation (task 6.5): the loader
 * concatenates these mapping errors with the structural and domain errors into
 * one {@code LoadOutcome}.
 *
 * <p><b>Settings (FR11, D5a).</b> Opaque {@code settings}/{@code params} maps are
 * already plain JDK types from Jackson's untyped binding (task 5.1); the mapper
 * copies them across defensively, so no Jackson type ever reaches the domain.
 *
 * <p>Order is preserved throughout: stages in {@code pipeline.yaml} order,
 * inputs/outputs/verify checks in {@code stage.yaml} declaration order.
 *
 * <p>Implements FR7, FR11, D2, D5a of load-pipeline-config.
 */
public final class PipelineMapper {

    private PipelineMapper() {}

    /**
     * One stage to map: its name (from the directory / {@code pipeline.yaml}
     * order, supplied by the loader) paired with its parsed manifest DTO.
     *
     * @param name the stage name, in {@code pipeline.yaml} order
     * @param dto the parsed, structurally-valid {@code stage.yaml} DTO
     */
    public record StageEntry(String name, StageDto dto) {}

    /**
     * The outcome of a mapping: either a fully mapped {@link PipelineDefinition}
     * (then {@link #errors()} is empty) or a {@code null} {@link #definition()}
     * with a non-empty list of located mapping errors (malformed durations).
     *
     * @param definition the mapped model, or {@code null} when mapping errored
     * @param errors the located mapping errors; empty exactly when a definition
     *     was produced
     */
    public record Result(@Nullable PipelineDefinition definition, List<ConfigError> errors) {

        public Result {
            errors = List.copyOf(errors);
        }
    }

    /**
     * Maps the config and ordered stage entries into a {@link Result}.
     *
     * <p>Implements FR7, FR11, D2, D5a of load-pipeline-config.
     *
     * @param config the parsed {@code config.yaml} DTO
     * @param entries the ordered stage entries, in {@code pipeline.yaml} order
     * @return a {@link Result} carrying the mapped definition, or the located
     *     mapping errors when a duration is malformed
     */
    public static Result map(ConfigDto config, List<StageEntry> entries) {
        int defaultLimit = attemptDefault(config);
        List<ConfigError> errors = new ArrayList<>();
        List<StageDefinition> stages = new ArrayList<>();
        for (StageEntry entry : entries) {
            stages.add(mapStage(entry, defaultLimit, errors));
        }
        if (!errors.isEmpty()) {
            return new Result(null, errors);
        }
        PipelineDefinition definition =
                new PipelineDefinition(orEmpty(config.schemaVersion()), new AutonomyLimits(defaultLimit), stages);
        return new Result(definition, List.of());
    }

    /** The config attempt-limit default; absent (no {@code autonomy}/limit) maps to 0 (FR7). */
    private static int attemptDefault(ConfigDto config) {
        AutonomyDto autonomy = config.autonomy();
        if (autonomy == null || autonomy.attemptLimit() == null) {
            return 0;
        }
        return autonomy.attemptLimit();
    }

    private static StageDefinition mapStage(StageEntry entry, int defaultLimit, List<ConfigError> errors) {
        String manifest = "stages/%s/stage.yaml".formatted(entry.name());
        StageDto dto = entry.dto();
        return new StageDefinition(
                entry.name(),
                orEmpty(dto.purpose()),
                mapInputs(dto.inputs()),
                mapOutputs(dto.outputs()),
                mapExecutor(dto.executor()),
                orEmpty(dto.instructions()),
                VerifyCheckMapper.mapAll(manifest, dto.verify(), errors),
                resolveLimits(defaultLimit, dto.autonomy()),
                mapAdvancement(dto.advancement()));
    }

    private static List<ArtifactInput> mapInputs(@Nullable List<ArtifactInputDto> inputs) {
        if (inputs == null) {
            return List.of();
        }
        List<ArtifactInput> mapped = new ArrayList<>();
        for (ArtifactInputDto input : inputs) {
            mapped.add(
                    switch (input) {
                        case ArtifactInputDto.Source ignored -> new ArtifactInput.Source();
                        case ArtifactInputDto.Internal internal ->
                            new ArtifactInput.Internal(orEmpty(internal.producerOutputId()));
                    });
        }
        return mapped;
    }

    private static List<ArtifactOutput> mapOutputs(@Nullable List<ArtifactOutputDto> outputs) {
        if (outputs == null) {
            return List.of();
        }
        List<ArtifactOutput> mapped = new ArrayList<>();
        for (ArtifactOutputDto output : outputs) {
            mapped.add(new ArtifactOutput(orEmpty(output.id())));
        }
        return mapped;
    }

    private static StageDefinition.Executor mapExecutor(@Nullable ExecutorDto executor) {
        if (executor == null) {
            return new StageDefinition.Executor(ExecutorType.API, "", Map.of());
        }
        return new StageDefinition.Executor(
                mapExecutorType(executor.type()), orEmpty(executor.model()), copySettings(executor.settings()));
    }

    /** Total on structurally-valid input (task 5.2 guaranteed a known value); absent maps to API. */
    private static ExecutorType mapExecutorType(@Nullable String type) {
        return "agent-cli".equals(type) ? ExecutorType.AGENT_CLI : ExecutorType.API;
    }

    /** Total on structurally-valid input (task 5.2 guaranteed a known value); absent maps to AUTO. */
    private static AdvancementMode mapAdvancement(@Nullable String advancement) {
        return "manual".equals(advancement) ? AdvancementMode.MANUAL : AdvancementMode.AUTO;
    }

    private static AutonomyLimits resolveLimits(int defaultLimit, @Nullable AutonomyDto autonomy) {
        Integer override = autonomy == null ? null : autonomy.attemptLimit();
        return AutonomyLimits.resolve(defaultLimit, override);
    }

    /** A defensive plain-JDK copy of an opaque settings/params map (FR11, D5a); absent maps to empty. */
    static Map<String, Object> copySettings(@Nullable Map<String, Object> settings) {
        return settings == null ? Map.of() : Map.copyOf(settings);
    }

    /** A {@code null} wire string becomes blank, so a domain rule (task 4.x) reports it, not an NPE. */
    static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
