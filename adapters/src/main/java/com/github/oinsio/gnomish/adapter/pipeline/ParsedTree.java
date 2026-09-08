package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.adapter.pipeline.GnomishFiles.RawConfig;
import com.github.oinsio.gnomish.adapter.pipeline.GnomishFiles.RawStage;
import com.github.oinsio.gnomish.adapter.pipeline.StructuralParse.Ok;
import com.github.oinsio.gnomish.adapter.pipeline.StructuralParse.Result;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The read tree once it is text no longer: every {@code .gnomish/} file parsed into its DTO, and
 * every parsed DTO shape-checked — the two tiers that answer "is this even a configuration tree"
 * before any tier asks what it means.
 *
 * <p>Extracted from {@link PipelineLoader} with its behavior, and its aggregation order, unchanged:
 * the loader kept the parse and shape tiers as private helpers, which left it owning both the
 * orchestration of every tier and the mechanics of two of them. The tiers here need only the raw
 * text; the tiers left there need the whole registry set. The split is that line.
 *
 * <p>Per-file short-circuit (design D6 of load-pipeline-config): a file that will not parse
 * contributes one located error and silences its own shape check alone — every other file still
 * reports its own problems.
 *
 * <p>Implements FR1, FR5, FR8 of load-pipeline-config.
 *
 * @param config the parsed {@code config.yaml}, or its one parse error
 * @param pipeline the parsed {@code pipeline.yaml}, or its one parse error
 * @param stages each discovered stage manifest that parsed, keyed by stage name in discovery order
 */
record ParsedTree(Result<ConfigDto> config, Result<PipelineDto> pipeline, Map<String, StageDto> stages) {

    private static final String CONFIG = "config.yaml";
    private static final String PIPELINE = "pipeline.yaml";

    /**
     * Parses every file of the read tree, appending each file's parse error to {@code errors}.
     *
     * @param raw the tree as {@link GnomishFiles#read} returned it
     * @param errors the shared single-pass error list; appended to, never read
     * @return the parsed tree, whatever parsed
     */
    static ParsedTree parse(RawConfig raw, List<ConfigError> errors) {
        Result<ConfigDto> config = StructuralParse.parse(CONFIG, raw.configText(), ConfigDto.class);
        Result<PipelineDto> pipeline = StructuralParse.parse(PIPELINE, raw.pipelineText(), PipelineDto.class);
        Map<String, StageDto> stages = parseStages(raw.stages(), errors);
        collectParse(errors, config, pipeline);
        return new ParsedTree(config, pipeline, stages);
    }

    /**
     * Structural shape checks on the parsed-OK DTOs (a failed parse short-circuits its own shape).
     * {@code config.yaml} needs no shape check — its only required field, {@code schemaVersion}, is
     * the domain {@code SchemaVersionRule}'s concern — so only {@code pipeline.yaml} and each stage
     * manifest are checked.
     *
     * @param errors the shared single-pass error list; appended to, never read
     */
    void checkShape(List<ConfigError> errors) {
        if (pipeline instanceof Ok<PipelineDto>(PipelineDto value)) {
            errors.addAll(StructuralValidation.checkPipeline(value));
        }
        for (Map.Entry<String, StageDto> entry : stages.entrySet()) {
            errors.addAll(StructuralValidation.checkStage(manifest(entry.getKey()), entry.getValue()));
        }
    }

    /**
     * The parsed {@code config.yaml}, for the tiers that read one section of it directly rather than
     * through the mapped model.
     *
     * @return the DTO, or null when the file did not parse
     */
    @Nullable
    ConfigDto configDto() {
        return config instanceof Ok<ConfigDto>(ConfigDto value) ? value : null;
    }

    /**
     * The pipeline stage names in declaration order.
     *
     * @return the names, or an empty list when {@code pipeline.yaml} did not parse cleanly or
     *     declares no {@code stages} key at all
     */
    List<String> pipelineStageNames() {
        if (pipeline instanceof Ok<PipelineDto>(PipelineDto value) && value.stages() != null) {
            return value.stages();
        }
        return List.of();
    }

    /** Parses each discovered manifest (skipping null-text ones), keyed by name in discovery order. */
    private static Map<String, StageDto> parseStages(List<RawStage> discovered, List<ConfigError> errors) {
        Map<String, StageDto> parsed = new LinkedHashMap<>();
        for (RawStage stage : discovered) {
            String text = stage.text();
            if (text == null) {
                continue;
            }
            String file = manifest(stage.name());
            switch (StructuralParse.parse(file, text, StageDto.class)) {
                case Ok<StageDto> ok -> parsed.put(stage.name(), ok.value());
                case StructuralParse.Failed<StageDto> failed -> errors.addAll(failed.errors());
            }
        }
        return parsed;
    }

    /** Appends config then pipeline parse errors, keeping the coarsest-file-first order. */
    private static void collectParse(List<ConfigError> errors, Result<ConfigDto> config, Result<PipelineDto> pipeline) {
        if (config instanceof StructuralParse.Failed<ConfigDto>(List<ConfigError> errors1)) {
            errors.addAll(errors1);
        }
        if (pipeline instanceof StructuralParse.Failed<PipelineDto>(List<ConfigError> errors1)) {
            errors.addAll(errors1);
        }
    }

    private static String manifest(String stageName) {
        return "stages/%s/stage.yaml".formatted(stageName);
    }
}
