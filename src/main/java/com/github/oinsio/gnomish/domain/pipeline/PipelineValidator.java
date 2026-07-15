package com.github.oinsio.gnomish.domain.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * The pure validation aggregator (design D6): runs every pure rule over a
 * {@link PipelineDefinition} in one pass and concatenates their located
 * {@link ConfigError}s into a single immutable list, so the author fixes every
 * problem in a single pass rather than one error per run (FR8, UX1). Aggregation
 * never short-circuits on the first failing rule and never throws — validation
 * problems are data (design D3).
 *
 * <p>Concatenation order (deterministic, NFR-R1) follows file granularity,
 * coarsest first, so the author reads problems roughly top-down through the
 * {@code .gnomish/} tree:
 *
 * <ol>
 *   <li>{@link SchemaVersionRule} — {@code config.yaml} tree-wide version (FR9);</li>
 *   <li>{@link StageOrderRule} — {@code pipeline.yaml} order and stage-name
 *       uniqueness (FR3, FR6);</li>
 *   <li>{@link ArtifactGraphRule} — pipeline-wide artifact DAG across stage
 *       manifests (FR3, FR4, FR6);</li>
 *   <li>{@link StageSanityRule} — per-stage mechanism and check local sanity
 *       (FR11, FR7).</li>
 * </ol>
 *
 * <p>Each delegated rule keeps its own internal ordering and reporting contract;
 * this aggregator only concatenates and never re-orders across rules. Every rule
 * runs unconditionally so its contribution is always observable. Scope: only the
 * pure (catalog-free) domain rules run here — I/O-bound adapter checks
 * (file existence, path traversal, structural deserialization) are aggregated
 * into the same {@link ConfigError} list by the loader (design D6, tasks 5–6),
 * not here.
 *
 * <p>Implements FR8 of load-pipeline-config.
 */
public final class PipelineValidator {

    private PipelineValidator() {}

    /**
     * Runs all pure rules over the model and returns the full, deterministically
     * ordered, immutable list of located {@link ConfigError}s — empty when the
     * model violates no pure rule.
     *
     * <p>Implements FR8 of load-pipeline-config.
     *
     * @param model the typed pipeline model to validate
     * @return every pure-rule error, concatenated in the documented order;
     *     immutable and possibly empty
     */
    public static List<ConfigError> validate(PipelineDefinition model) {
        List<ConfigError> errors = new ArrayList<>();
        errors.addAll(SchemaVersionRule.validate(model.schemaVersion()));
        errors.addAll(StageOrderRule.validate(model.stages()));
        errors.addAll(ArtifactGraphRule.validate(model.stages()));
        errors.addAll(StageSanityRule.validate(model.stages()));
        return List.copyOf(errors);
    }
}
