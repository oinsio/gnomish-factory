package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.adapter.law.LawSource;
import com.github.oinsio.gnomish.adapter.pipeline.PipelineMapper.StageEntry;
import com.github.oinsio.gnomish.adapter.pipeline.StructuralParse.Ok;
import com.github.oinsio.gnomish.app.TrackerSubsectionValidator;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.PipelineValidator;
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
 * last, over the mapped stages, since it grades each check's settings by the
 * executor the mapper has just recorded; the http targets are then judged against the operator's
 * in-process egress scope ({@link ManifestTargetSeam}, FR17, design D16 of
 * migrate-verify-to-executor-contract).
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
     * <p>Takes the {@link ParsedTree} whole rather than its three components
     * (FR6 of add-parameter-count-gate, design D12).
     *
     * <p>{@code checkProviders} maps a built-in executor's name to the params
     * validator the check seam grades its checks' settings with; {@code agentSettings} is the
     * agent executor's settings judge, handed every agent-cli stage's and judge check's settings
     * ({@link AgentSettingsValidator}; FR7 of extract-agent-round-runner).
     *
     * @return the mapped, validated definition, or {@code null} when it could not
     *     be produced
     */
    static @Nullable PipelineDefinition mapAndValidate(
            LawSource law,
            ParsedTree tree,
            Map<String, TrackerSubsectionValidator> trackerValidators,
            Map<String, CheckSettingsValidator> checkProviders,
            CheckSettingsValidator agentSettings,
            ConnectionAdmission connections,
            List<ConfigError> errors) {
        if (!(tree.config() instanceof Ok<ConfigDto>(ConfigDto value))
                || !(tree.pipeline() instanceof Ok<PipelineDto>(PipelineDto value1))) {
            return null;
        }
        List<String> pipelineNames = value1.stages();
        if (pipelineNames == null) {
            return null;
        }
        List<StageEntry> entries = orderedEntries(pipelineNames, tree.stages());
        if (entries == null) {
            return null;
        }
        PipelineMapper.Result mapped = PipelineMapper.map(value, entries, connections);
        errors.addAll(mapped.errors());
        errors.addAll(TrackerSeamValidator.validate(
                "config.yaml", value.tracker(), trackerValidators, connections.profiles()));
        PipelineDefinition model = mapped.definition();
        if (model == null) {
            return null;
        }
        errors.addAll(PipelineValidator.validate(model));
        errors.addAll(ReferencedFiles.check(law, model.stages()));
        errors.addAll(AgentSettingsValidator.validate(model, agentSettings));
        errors.addAll(CommandSettingsValidator.validate(model));
        errors.addAll(ExternalCheckSeamValidator.validate(model.stages(), checkProviders));
        errors.addAll(ManifestTargetSeam.judge(model.stages(), connections.operator()));
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
