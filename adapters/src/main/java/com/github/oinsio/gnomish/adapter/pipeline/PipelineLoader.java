package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.adapter.law.LawSource;
import com.github.oinsio.gnomish.adapter.law.WorkingTreeLawSource;
import com.github.oinsio.gnomish.adapter.pipeline.GnomishFiles.RawConfig;
import com.github.oinsio.gnomish.app.CheckParamsValidator;
import com.github.oinsio.gnomish.app.ConnectionProfiles;
import com.github.oinsio.gnomish.app.TrackerSubsectionValidator;
import com.github.oinsio.gnomish.app.port.pipeline.ConfiguredDesignatorKinds;
import com.github.oinsio.gnomish.baseref.BaseDefinition;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 *       {@link AgentSettingsValidator}
 *       (agent-cli/judge settings schema, task 9.1 of add-agent-executor), run
 *       only when a {@link PipelineDefinition} was produced;</li>
 *   <li><b>check-seam</b> — {@link ExternalCheckSeamValidator} (FR6, FR13 of
 *       add-plugin-architecture): each {@code external} check's provider against the
 *       discovered registry, and its provider-owned {@code params} against that
 *       provider's own validator; like the other model-dependent tiers it runs only
 *       when a {@link PipelineDefinition} was produced.</li>
 * </ol>
 *
 * <p><b>Aggregation order (deterministic, NFR-R1).</b> Errors are concatenated
 * coarsest-file-first, in tier order: parse (config, pipeline, then stages in
 * discovery order), structural (same order), consistency, mapping, tracker-seam,
 * domain, referenced-files, settings, then check-seam. The same tree always yields
 * an equal outcome.
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
     * <p>The check seam is delegated the same way (FR6, FR13 of add-plugin-architecture): {@code
     * checkProviders} is keyed by every discovered check provider, so an {@code external} check
     * naming one nobody serves — including the {@code github} the loader defaults to when a
     * manifest names none — is a located load error, and a served check's provider-owned {@code
     * params} are graded by that provider's own validator.
     *
     * <p>Implements FR1, FR8 of load-pipeline-config; FR17 of add-tracker-port; FR6, FR13 of
     * add-plugin-architecture.
     *
     * @param gnomishRoot the {@code .gnomish/} directory root
     * @param trackerValidators known adapter subsection validators, keyed by {@code tracker.type};
     *     an empty map means no adapters are known and every declared type is reported unknown
     * @param checkProviders known check providers' params validators, keyed by {@code provider}; an
     *     empty map means no provider is known and every {@code external} check's provider is
     *     reported unknown
     * @return {@link LoadOutcome.Loaded} with the validated model when the tree has
     *     no problem, else {@link LoadOutcome.Invalid} with every located error
     * @throws IOException when a required file cannot be read (an I/O fault, never a
     *     validation problem — FR8/D3)
     */
    public static LoadOutcome load(
            Path gnomishRoot,
            Map<String, TrackerSubsectionValidator> trackerValidators,
            Map<String, CheckParamsValidator> checkProviders)
            throws IOException {
        return load(gnomishRoot, trackerValidators, checkProviders, ConnectionProfiles.none());
    }

    /**
     * The connection-aware form (FR16, design D8/D12 of add-plugin-architecture): identical, except
     * that the operator's named connection profiles travel down with the registries, so a {@code
     * tracker.<type>} subsection referencing one as {@code connection: <name>} is validated against
     * the defined set at load time and mapped with the reference already resolved.
     *
     * @param profiles the operator-declared {@code factory.connections} profiles; an empty set means
     *     every {@code connection:} reference is an undefined one, reported as a located load error
     */
    public static LoadOutcome load(
            Path gnomishRoot,
            Map<String, TrackerSubsectionValidator> trackerValidators,
            Map<String, CheckParamsValidator> checkProviders,
            ConnectionProfiles profiles)
            throws IOException {
        return loadConfiguration(gnomishRoot, trackerValidators, checkProviders, profiles, Set.of())
                .outcome();
    }

    /**
     * The whole of one pass (FR1, FR2 of add-base-ref-resolution): the task tier's
     * {@link LoadOutcome} <em>and</em> the trusted tier's {@link BaseDefinition}, from one read of
     * the tree. Every {@code load} overload above is this method with no designator kinds declared,
     * keeping the base definition out of the way of callers that only want the pipeline.
     *
     * <p>The {@code task-branch.base} tier runs on the parsed {@code config.yaml} alone, so a broken
     * {@code pipeline.yaml} never hides a broken base section and vice versa, and its errors join the same
     * one-pass aggregate (UX1). It is followed by the designator seam
     * ({@link DesignatorAllowedBasesSeam}), the one check that needs both the adapter's extraction rule
     * and the allowed bases in hand.
     *
     * <p>The tree is read from wherever this invocation's law is bound (design D12 of
     * add-base-ref-resolution) — the working tree, or the law commit's own tree in git objects; the
     * {@link Path} overload below is the working-tree boundary for a caller holding a directory.
     *
     * @param law the law source rooted at the {@code .gnomish/} tree
     * @param configuredDesignatorKinds the designator kinds the configured tracker adapter reports
     *     it extracts (design D5); empty when none is configured, under which the seam check has
     *     nothing to hold a selection against
     * @return the task tier's outcome and the trusted tier's base definition
     * @throws IOException when a required file cannot be read (an I/O fault, never a validation
     *     problem — FR8/D3)
     */
    public static ConfigurationLoad loadConfiguration(
            LawSource law,
            Map<String, TrackerSubsectionValidator> trackerValidators,
            Map<String, CheckParamsValidator> checkProviders,
            ConnectionProfiles profiles,
            Set<String> configuredDesignatorKinds)
            throws IOException {
        return loadConfiguration(law, trackerValidators, checkProviders, profiles, _ -> configuredDesignatorKinds);
    }

    /**
     * The composition root's form of the pass (FR3, FR13 of add-base-ref-resolution): the
     * designator kinds are asked of {@code designatorKinds} once the {@code tracker} section has
     * been mapped — the adapter that answers is chosen by {@code tracker.type}, and its rules live
     * in the subsection, so a fixed set cannot be known before the read. A tree with no mappable
     * tracker section asks nothing and the seam has nothing to hold a selection against.
     *
     * @param designatorKinds the adapter-side answer, keyed on the mapped tracker section
     */
    public static ConfigurationLoad loadConfiguration(
            LawSource law,
            Map<String, TrackerSubsectionValidator> trackerValidators,
            Map<String, CheckParamsValidator> checkProviders,
            ConnectionProfiles profiles,
            ConfiguredDesignatorKinds designatorKinds)
            throws IOException {
        RawConfig raw = GnomishFiles.read(law);
        List<ConfigError> errors = new ArrayList<>();

        ParsedTree tree = ParsedTree.parse(raw, errors);

        TrustedTierSections trusted = new TrustedTierSections(tree.configDto());
        BaseDefinition base = BaseConfigMapper.map(trusted.baseSection(), errors);
        errors.addAll(DesignatorAllowedBasesSeam.check(
                trusted.trackerType(), trusted.designatorKinds(profiles, designatorKinds), base));

        tree.checkShape(errors);
        errors.addAll(StageConsistency.check(tree.pipelineStageNames(), raw.stages()));

        PipelineDefinition model = PipelineModelBuilder.mapAndValidate(
                law,
                tree.config(),
                tree.pipeline(),
                tree.stages(),
                trackerValidators,
                checkProviders,
                profiles,
                errors);

        LoadOutcome outcome =
                errors.isEmpty() && model != null ? new LoadOutcome.Loaded(model) : new LoadOutcome.Invalid(errors);
        return new ConfigurationLoad(outcome, base);
    }

    /**
     * The working-tree boundary form of the pass above: the same read of the {@code .gnomish/}
     * directory rooted at {@code gnomishRoot} — the in-place mode and manual {@code run} without
     * {@code --base}, where an uncommitted edit is meant to be law (FR11 of add-base-ref-resolution).
     *
     * @param gnomishRoot the {@code .gnomish/} directory root
     * @return the task tier's outcome and the trusted tier's base definition
     * @throws IOException when a required file cannot be read
     */
    public static ConfigurationLoad loadConfiguration(
            Path gnomishRoot,
            Map<String, TrackerSubsectionValidator> trackerValidators,
            Map<String, CheckParamsValidator> checkProviders,
            ConnectionProfiles profiles,
            Set<String> configuredDesignatorKinds)
            throws IOException {
        return loadConfiguration(
                new WorkingTreeLawSource(gnomishRoot),
                trackerValidators,
                checkProviders,
                profiles,
                configuredDesignatorKinds);
    }

    private PipelineLoader() {}
}
