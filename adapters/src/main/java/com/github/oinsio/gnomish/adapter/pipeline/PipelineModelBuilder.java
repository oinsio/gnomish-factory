package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.adapter.pipeline.PipelineMapper.StageEntry;
import com.github.oinsio.gnomish.adapter.pipeline.StructuralParse.Ok;
import com.github.oinsio.gnomish.adapter.pipeline.StructuralParse.Result;
import com.github.oinsio.gnomish.app.CheckParamsValidator;
import com.github.oinsio.gnomish.app.ConnectionProfiles;
import com.github.oinsio.gnomish.app.TrackerSubsectionValidator;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.PipelineValidator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The model-building tier of {@link PipelineLoader} (design D1): builds the
 * domain {@link PipelineDefinition} from the parsed-OK DTOs, or reports why it
 * could not be built, when the tree is clean enough to attempt (the "skip when
 * unbuildable" logic). Also runs {@link TrackerSeamValidator} (FR17 of
 * add-tracker-port) right after mapping, since it needs the parsed {@code
 * tracker} DTO that mapping already has in hand; its errors append to the same
 * shared list as every other tier here. The check seam
 * ({@link ExternalCheckSeamValidator}, FR6/FR13 of add-plugin-architecture) runs
 * last, over the mapped stages, since it grades the provider selection the mapper
 * has just recorded.
 *
 * <p>Implements FR1, FR3 of fix-oversized-adapters (D1): extracted from
 * {@link PipelineLoader} with identical {@code @Nullable} return semantics and
 * the same error-append contract — callers pass the shared error list and this
 * class only appends to it, never replaces or reorders it.
 */
final class PipelineModelBuilder {

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
    static @Nullable PipelineDefinition mapAndValidate(
            Path root,
            Result<ConfigDto> config,
            Result<PipelineDto> pipeline,
            Map<String, StageDto> stages,
            Map<String, TrackerSubsectionValidator> trackerValidators,
            Map<String, CheckParamsValidator> checkProviders,
            ConnectionProfiles profiles,
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
        PipelineMapper.Result mapped = PipelineMapper.map(value, entries, profiles);
        errors.addAll(mapped.errors());
        errors.addAll(TrackerSeamValidator.validate("config.yaml", value.tracker(), trackerValidators, profiles));
        PipelineDefinition model = mapped.definition();
        if (model == null) {
            return null;
        }
        errors.addAll(PipelineValidator.validate(model));
        errors.addAll(ReferencedFiles.check(root, model.stages()));
        errors.addAll(AgentSettingsValidator.validate(model));
        errors.addAll(ExternalCheckSeamValidator.validate(model.stages(), checkProviders));
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

    private PipelineModelBuilder() {}
}
