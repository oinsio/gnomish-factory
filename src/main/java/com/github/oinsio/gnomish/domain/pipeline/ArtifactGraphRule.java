package com.github.oinsio.gnomish.domain.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.stream.Collectors;

/**
 * The pure artifact-DAG consistency rule (design D4/D6): validates the artifact
 * graph <em>against</em> the stage order declared in {@code pipeline.yaml} (as
 * carried by {@link PipelineDefinition}), never deriving order from it. Output
 * {@code id}s must be unique across the whole pipeline; every
 * {@link ArtifactInput.Internal} input must resolve to an output {@code id} of a
 * strictly-earlier stage; {@link ArtifactInput.Source} inputs need no producer.
 * Problems are located {@link ConfigError}s naming the stage manifest where the
 * offending declaration lives (NFR-O1, UX2), never thrown (design D3).
 *
 * <p>Duplicate reporting contract: each duplicated id is reported exactly once
 * — not per occurrence — located at the <em>first</em> declaring stage's
 * manifest and naming every declaring stage in declaration order (the same
 * stage twice for a within-stage duplicate, per the delta-spec scenario).
 *
 * <p>Reference contract under duplicates: with a duplicated id the "earlier"
 * resolution could be ambiguous, so the check stays deterministic by resolving
 * an internal reference when ANY strictly-earlier stage declares the id — the
 * duplicate error already fires separately. Otherwise, the earliest declaration
 * (necessarily non-earlier) classifies the error: the consumer itself
 * (self-reference) or a later stage (forward, naming that earliest late
 * producer); an id no stage declares is dangling.
 *
 * <p>Error order is deterministic (NFR-R1): duplicate errors first, in
 * first-declaration order; then reference errors in pipeline order, then input
 * declaration order.
 *
 * <p>Implements FR4, FR6 (artifact-id clauses) and the DAG-consistency clause
 * of FR3 of load-pipeline-config.
 */
public final class ArtifactGraphRule {

    private ArtifactGraphRule() {}

    /**
     * Validates the artifact graph against the declared stage order: unique
     * output ids and strictly-earlier resolution of every internal input yield
     * no errors; each duplicated id and each unresolved reference yields
     * exactly one located {@link ConfigError} per the contracts above.
     *
     * <p>Implements FR4, FR6 (artifact-id clauses) and the DAG-consistency
     * clause of FR3 of load-pipeline-config.
     *
     * @param stages the stages in exactly the {@code pipeline.yaml} declaration
     *     order, as carried by {@link PipelineDefinition#stages()}
     */
    public static List<ConfigError> validate(List<StageDefinition> stages) {
        SequencedMap<String, List<Integer>> declarations = declarationsById(stages);
        List<ConfigError> errors = new ArrayList<>(duplicateIdErrors(stages, declarations));
        errors.addAll(referenceErrors(stages, declarations));
        return List.copyOf(errors);
    }

    /** Output ids mapped to the declaring stage indices, both in declaration order. */
    private static SequencedMap<String, List<Integer>> declarationsById(List<StageDefinition> stages) {
        SequencedMap<String, List<Integer>> declarations = new LinkedHashMap<>();
        for (int stageIndex = 0; stageIndex < stages.size(); stageIndex++) {
            for (ArtifactOutput output : stages.get(stageIndex).outputs()) {
                declarations
                        .computeIfAbsent(output.id(), _ -> new ArrayList<>())
                        .add(stageIndex);
            }
        }
        return declarations;
    }

    private static List<ConfigError> duplicateIdErrors(
            List<StageDefinition> stages, SequencedMap<String, List<Integer>> declarations) {
        List<ConfigError> errors = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> declared : declarations.entrySet()) {
            List<Integer> declaringStages = declared.getValue();
            if (declaringStages.size() > 1) {
                String stageNames = declaringStages.stream()
                        .map(stageIndex ->
                                "'%s'".formatted(stages.get(stageIndex).name()))
                        .collect(Collectors.joining(", "));
                errors.add(new ConfigError(
                        manifest(stages.get(declaringStages.getFirst()).name()),
                        "outputs[%s]".formatted(declared.getKey()),
                        "duplicate output id '%s' declared %d times by stages %s; output ids must be unique across the pipeline"
                                .formatted(declared.getKey(), declaringStages.size(), stageNames)));
            }
        }
        return errors;
    }

    private static List<ConfigError> referenceErrors(
            List<StageDefinition> stages, SequencedMap<String, List<Integer>> declarations) {
        List<ConfigError> errors = new ArrayList<>();
        for (int consumerIndex = 0; consumerIndex < stages.size(); consumerIndex++) {
            for (ArtifactInput input : stages.get(consumerIndex).inputs()) {
                if (input instanceof ArtifactInput.Internal(String producerOutputId)) {
                    checkReference(stages, declarations, consumerIndex, producerOutputId, errors);
                }
            }
        }
        return errors;
    }

    /** Classifies one internal reference per the reference contract above. */
    private static void checkReference(
            List<StageDefinition> stages,
            SequencedMap<String, List<Integer>> declarations,
            int consumerIndex,
            String producerOutputId,
            List<ConfigError> errors) {
        String consumer = stages.get(consumerIndex).name();
        String where = "inputs[%s]".formatted(producerOutputId);
        List<Integer> declaringStages = declarations.get(producerOutputId);
        if (declaringStages == null) {
            errors.add(new ConfigError(
                    manifest(consumer),
                    where,
                    "internal input references output id '%s', which no stage produces".formatted(producerOutputId)));
            return;
        }
        int earliestProducer = declaringStages.getFirst();
        if (earliestProducer < consumerIndex) {
            return; // resolves: some strictly-earlier stage declares the id
        }
        if (earliestProducer == consumerIndex) {
            errors.add(new ConfigError(
                    manifest(consumer),
                    where,
                    "internal input references output id '%s', which is produced by stage '%s' itself; the producer must be an earlier stage"
                            .formatted(producerOutputId, consumer)));
            return;
        }
        errors.add(new ConfigError(
                manifest(consumer),
                where,
                "internal input references output id '%s', which is first produced by later stage '%s'; the producer must be an earlier stage"
                        .formatted(
                                producerOutputId, stages.get(earliestProducer).name())));
    }

    private static String manifest(String stageName) {
        return "stages/%s/stage.yaml".formatted(stageName);
    }
}
