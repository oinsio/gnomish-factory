package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.adapter.pipeline.GnomishFiles.RawConfig;
import com.github.oinsio.gnomish.adapter.pipeline.GnomishFiles.RawStage;
import com.github.oinsio.gnomish.adapter.pipeline.StructuralParse.Ok;
import com.github.oinsio.gnomish.adapter.pipeline.StructuralParse.Result;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *   <li><b>tracker-seam</b> — {@link TrackerSeamValidator} (FR17 of
 *       add-tracker-port): runs alongside mapping on the parsed {@code tracker}
 *       DTO — unknown {@code type}, missing/mismatched subsection, and any
 *       delegated adapter-validator errors, independent of whether a full
 *       {@link PipelineDefinition} could be built;</li>
 *   <li><b>domain-validate</b>, <b>I/O-validate</b> and <b>settings-validate</b> —
 *       {@link com.github.oinsio.gnomish.domain.pipeline.PipelineValidator} (pure
 *       semantic rules), {@link ReferencedFiles} (file existence + traversal), and
 *       {@link com.github.oinsio.gnomish.adapter.agent.AgentSettingsValidator}
 *       (agent-cli/judge settings schema, task 9.1 of add-agent-executor), run
 *       only when a {@link PipelineDefinition} was produced.</li>
 * </ol>
 *
 * <p><b>Aggregation order (deterministic, NFR-R1).</b> Errors are concatenated
 * coarsest-file-first, in tier order: parse (config, pipeline, then stages in
 * discovery order), structural (same order), consistency, mapping, tracker-seam,
 * domain, referenced-files, then settings. The same tree always yields an equal
 * outcome.
 *
 * <p><b>No execution (NFR-S1) / no writes (NFR-R1).</b> The loader only reads text,
 * parses, and validates: it never runs a configured {@code command}, model, or
 * {@code external} check (they are carried as inert data), and never creates,
 * modifies, or deletes anything under the root.
 *
 * <p>Implements FR1, FR8 (+ NFR-S1, NFR-R1) of load-pipeline-config; the
 * settings-validate tier additionally implements FR11, UX2, D7 of
 * add-agent-executor (task 9.1); the tracker-seam tier additionally implements
 * FR17 of add-tracker-port (task 3.2).
 */
public final class PipelineLoader {

    private static final String CONFIG = "config.yaml";
    private static final String PIPELINE = "pipeline.yaml";

    /**
     * Loads and validates the {@code .gnomish/} tree rooted at {@code gnomishRoot} with no known
     * tracker adapters — every {@code tracker.type} is reported unknown by the tracker-seam tier
     * (the documented empty-registry mode of {@link TrackerSeamValidator}). Suitable for callers
     * that never load a project with a {@code tracker:} section; production callers that do
     * ({@link com.github.oinsio.gnomish.app.PipelineStartup}, {@code TakeCommandSupport}) pass the
     * Spring-supplied registry via {@link #load(Path, Map)}.
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
        return load(gnomishRoot, Map.of());
    }

    /**
     * Loads and validates the {@code .gnomish/} tree rooted at {@code gnomishRoot}, delegating each
     * {@code tracker.<type>} subsection's content validation to {@code trackerValidators} (FR17 of
     * add-tracker-port): a subsection whose {@code type} has a registered validator is handed to it,
     * so an adapter-owned error (e.g. GitHub's bad hex color) is a located load error aggregated
     * with core errors in one pass. The registry is supplied by the composition root ({@code
     * TrackerAdapterConfiguration}) rather than referenced here, keeping {@code adapter.pipeline}
     * free of any {@code adapter.tracker} dependency (the
     * {@code TrackerPortBoundarySpec} gate).
     *
     * <p>Implements FR1, FR8 of load-pipeline-config; FR17 of add-tracker-port.
     *
     * @param gnomishRoot the {@code .gnomish/} directory root
     * @param trackerValidators known adapter subsection validators, keyed by {@code tracker.type};
     *     an empty map means no adapters are known and every declared type is reported unknown
     * @return {@link LoadOutcome.Loaded} with the validated model when the tree has
     *     no problem, else {@link LoadOutcome.Invalid} with every located error
     * @throws IOException when a required file cannot be read (an I/O fault, never a
     *     validation problem — FR8/D3)
     */
    public static LoadOutcome load(Path gnomishRoot, Map<String, TrackerSubsectionValidator> trackerValidators)
            throws IOException {
        RawConfig raw = GnomishFiles.read(gnomishRoot);
        List<ConfigError> errors = new ArrayList<>();

        Result<ConfigDto> config = StructuralParse.parse(CONFIG, raw.configText(), ConfigDto.class);
        Result<PipelineDto> pipeline = StructuralParse.parse(PIPELINE, raw.pipelineText(), PipelineDto.class);
        Map<String, StageDto> stages = parseStages(raw.stages(), errors);
        collectParse(errors, config, pipeline);

        structural(errors, pipeline, stages);

        List<String> pipelineNames = pipelineStageNames(pipeline);
        errors.addAll(StageConsistency.check(pipelineNames, raw.stages()));

        PipelineDefinition model =
                PipelineModelBuilder.mapAndValidate(gnomishRoot, config, pipeline, stages, trackerValidators, errors);

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

    private static String manifest(String stageName) {
        return "stages/%s/stage.yaml".formatted(stageName);
    }

    private PipelineLoader() {}
}
