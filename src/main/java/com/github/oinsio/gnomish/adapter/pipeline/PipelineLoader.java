package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.adapter.pipeline.GnomishFiles.RawConfig;
import com.github.oinsio.gnomish.adapter.pipeline.GnomishFiles.RawStage;
import com.github.oinsio.gnomish.adapter.pipeline.PipelineMapper.StageEntry;
import com.github.oinsio.gnomish.adapter.pipeline.StructuralParse.Ok;
import com.github.oinsio.gnomish.adapter.pipeline.StructuralParse.Result;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.PipelineValidator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The composition point of the whole capability (task 6.5, FR1/FR8): turns a
 * {@code .gnomish/} directory {@link Path} into one {@link LoadOutcome} — either a
 * validated {@link PipelineDefinition} or the complete, located problem list — by
 * wiring the read, parse, structural, consistency, mapping, domain-validation and
 * I/O-validation tiers together and aggregating every {@link ConfigError} they
 * produce into a single pass (UX1).
 *
 * <p><b>Exception contract (FR8, design D3).</b> Validation problems are data:
 * they are returned as {@link LoadOutcome.Invalid}, never thrown. Only a genuine
 * I/O fault — an unreadable required {@code config.yaml}/{@code pipeline.yaml} — is
 * an exception, propagated as the {@link IOException} {@link GnomishFiles#read}
 * raises. The caller therefore distinguishes "the configuration is wrong" (Invalid)
 * from "the configuration cannot be read" (IOException).
 *
 * <p><b>Orchestration order and layered short-circuit (design D6).</b> Tiers run in
 * a fixed dependency order; a tier runs only when its inputs exist, but is never
 * skipped merely because an earlier <em>independent</em> tier failed:
 *
 * <ol>
 *   <li><b>read</b> — {@link GnomishFiles#read} (I/O faults escape here);</li>
 *   <li><b>parse</b> — {@link StructuralParse} on {@code config.yaml},
 *       {@code pipeline.yaml} and each discovered stage manifest; a file that will
 *       not parse contributes one located error and short-circuits <em>only its
 *       own</em> downstream shape/mapping checks, other files proceed (Risks);</li>
 *   <li><b>structural</b> — {@link StructuralValidation} on the parsed-OK DTOs;</li>
 *   <li><b>consistency</b> — {@link StageConsistency}: {@code pipeline.yaml} order
 *       vs the discovered stage directories (needs only the parsed pipeline names
 *       and the raw stages);</li>
 *   <li><b>map</b> — {@link PipelineMapper}: run only when {@code config.yaml} and
 *       {@code pipeline.yaml} parsed and every pipeline-named stage has a
 *       structurally-clean parsed DTO, since a domain model cannot be built from a
 *       partial or malformed tree;</li>
 *   <li><b>domain-validate</b> and <b>I/O-validate</b> —
 *       {@link PipelineValidator} (pure semantic rules) and
 *       {@link ReferencedFiles} (file existence + traversal), run only when a
 *       {@link PipelineDefinition} was produced.</li>
 * </ol>
 *
 * <p><b>Aggregation order (deterministic, NFR-R1).</b> Errors are concatenated
 * coarsest-file-first, in tier order: parse (config, pipeline, then stages in
 * discovery order), structural (same order), consistency, mapping, domain, then
 * referenced-files. The same tree always yields an equal outcome.
 *
 * <p><b>No execution (NFR-S1) / no writes (NFR-R1).</b> The loader only reads text,
 * parses, and validates: it never runs a configured {@code command}, model, or
 * {@code external} check (they are carried as inert data), and never creates,
 * modifies, or deletes anything under the root.
 *
 * <p>Implements FR1, FR8 (+ NFR-S1, NFR-R1) of load-pipeline-config.
 */
public final class PipelineLoader {

    private static final String CONFIG = "config.yaml";
    private static final String PIPELINE = "pipeline.yaml";

    /**
     * Loads and validates the {@code .gnomish/} tree rooted at {@code gnomishRoot}.
     *
     * <p>Implements FR1, FR8 of load-pipeline-config.
     *
     * @param gnomishRoot the {@code .gnomish/} directory root
     * @return {@link LoadOutcome.Loaded} with the validated model when the tree has
     *     no problem, else {@link LoadOutcome.Invalid} with every located error
     * @throws IOException when a required file cannot be read (an I/O fault, never a
     *     validation problem — FR8/D3)
     */
    public static LoadOutcome load(Path gnomishRoot) throws IOException {
        RawConfig raw = GnomishFiles.read(gnomishRoot);
        List<ConfigError> errors = new ArrayList<>();

        Result<ConfigDto> config = StructuralParse.parse(CONFIG, raw.configText(), ConfigDto.class);
        Result<PipelineDto> pipeline = StructuralParse.parse(PIPELINE, raw.pipelineText(), PipelineDto.class);
        Map<String, StageDto> stages = parseStages(raw.stages(), errors);
        collectParse(errors, config, pipeline);

        structural(errors, pipeline, stages);

        List<String> pipelineNames = pipelineStageNames(pipeline);
        errors.addAll(StageConsistency.check(pipelineNames, raw.stages()));

        PipelineDefinition model = mapAndValidate(gnomishRoot, config, pipeline, stages, errors);

        if (errors.isEmpty() && model != null) {
            return new LoadOutcome.Loaded(model);
        }
        return new LoadOutcome.Invalid(errors);
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

    /**
     * Structural shape checks on the parsed-OK DTOs (a failed parse short-circuits
     * its own shape). {@code config.yaml} needs no shape check — its only required
     * field, {@code schemaVersion}, is the domain {@code SchemaVersionRule}'s
     * concern — so only {@code pipeline.yaml} and each stage manifest are checked.
     */
    private static void structural(
            List<ConfigError> errors, Result<PipelineDto> pipeline, Map<String, StageDto> stages) {
        if (pipeline instanceof Ok<PipelineDto>(PipelineDto value)) {
            errors.addAll(StructuralValidation.checkPipeline(value));
        }
        for (Map.Entry<String, StageDto> entry : stages.entrySet()) {
            errors.addAll(StructuralValidation.checkStage(manifest(entry.getKey()), entry.getValue()));
        }
    }

    /** The pipeline stage names in declaration order, or empty when pipeline.yaml did not parse cleanly. */
    private static List<String> pipelineStageNames(Result<PipelineDto> pipeline) {
        if (pipeline instanceof Ok<PipelineDto>(PipelineDto value) && value.stages() != null) {
            return value.stages();
        }
        return List.of();
    }

    /**
     * Maps and semantically validates only when a domain model can be built: both
     * top-level files parsed, {@code pipeline.yaml} carries its {@code stages} key
     * (an <em>empty</em> list qualifies — the domain {@code StageOrderRule} must run
     * to report it and {@code SchemaVersionRule} to check the version), and every
     * pipeline-named stage has a parsed DTO. When {@code config.yaml} or
     * {@code pipeline.yaml} did not parse, or a named stage's manifest is absent or
     * unparseable, the model cannot be built and the model-dependent tiers are
     * skipped (D6); the earlier tiers' errors already explain why.
     *
     * @return the mapped, validated definition, or {@code null} when it could not
     *     be produced
     */
    private static @Nullable PipelineDefinition mapAndValidate(
            Path root,
            Result<ConfigDto> config,
            Result<PipelineDto> pipeline,
            Map<String, StageDto> stages,
            List<ConfigError> errors) {
        if (!(config instanceof Ok<ConfigDto>(ConfigDto value))
                || !(pipeline instanceof Ok<PipelineDto>(PipelineDto value1))) {
            return null;
        }
        List<String> pipelineNames = value1.stages();
        if (pipelineNames == null) {
            return null;
        }
        List<StageEntry> entries = orderedEntries(pipelineNames, stages);
        if (entries == null) {
            return null;
        }
        PipelineMapper.Result mapped = PipelineMapper.map(value, entries);
        errors.addAll(mapped.errors());
        PipelineDefinition model = mapped.definition();
        if (model == null) {
            return null;
        }
        errors.addAll(PipelineValidator.validate(model));
        errors.addAll(ReferencedFiles.check(root, model.stages()));
        return model;
    }

    /**
     * Builds the ordered stage entries from the pipeline order, or {@code null} when
     * any pipeline-named stage lacks a parsed DTO — mapping the whole pipeline then
     * makes no sense (the consistency and structural tiers already located the gap).
     */
    private static @Nullable List<StageEntry> orderedEntries(List<String> pipelineNames, Map<String, StageDto> stages) {
        List<StageEntry> entries = new ArrayList<>();
        for (String name : pipelineNames) {
            StageDto dto = stages.get(name);
            if (dto == null) {
                return null;
            }
            entries.add(new StageEntry(name, dto));
        }
        return entries;
    }

    private static String manifest(String stageName) {
        return "stages/%s/stage.yaml".formatted(stageName);
    }

    private PipelineLoader() {}
}
