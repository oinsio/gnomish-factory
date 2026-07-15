package com.github.oinsio.gnomish.domain.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * The pure stage-order rule (design D4/D6): checks that the stage sequence
 * carried by {@link PipelineDefinition} — declared explicitly in
 * {@code pipeline.yaml}, never derived from the artifact DAG — is a non-empty
 * linear sequence of unique stage names. Problems are reported as located
 * {@link ConfigError}s naming {@code pipeline.yaml}, the file where order and
 * names are declared (NFR-O1, UX2), never thrown (design D3).
 *
 * <p>Duplicate reporting contract: each duplicated name is reported exactly
 * once — not per occurrence — naming the stage and its total occurrence count,
 * in first-occurrence order. One error per root cause keeps the list fixable
 * in a single pass (UX1) and deterministic (NFR-R1).
 *
 * <p>Implements FR3 and the unique-stage-names clause of FR6 of
 * load-pipeline-config.
 */
public final class StageOrderRule {

    private static final String FILE = "pipeline.yaml";

    private StageOrderRule() {}

    /**
     * Validates the declared stage order: a non-empty sequence of unique names
     * yields no errors; an empty sequence yields exactly one located
     * {@link ConfigError}; each duplicated name yields exactly one located
     * {@link ConfigError} naming it and its occurrence count.
     *
     * <p>Implements FR3 and the unique-stage-names clause of FR6 of
     * load-pipeline-config.
     *
     * @param stages the stages in exactly the {@code pipeline.yaml} declaration
     *     order, as carried by {@link PipelineDefinition#stages()}
     */
    public static List<ConfigError> validate(List<StageDefinition> stages) {
        if (stages.isEmpty()) {
            return List.of(new ConfigError(FILE, "stages", "pipeline declares no stages"));
        }
        SequencedMap<String, Integer> occurrences = new LinkedHashMap<>();
        for (StageDefinition stage : stages) {
            occurrences.merge(stage.name(), 1, Integer::sum);
        }
        List<ConfigError> errors = getConfigErrors(occurrences);
        return List.copyOf(errors);
    }

    private static List<ConfigError> getConfigErrors(SequencedMap<String, Integer> occurrences) {
        List<ConfigError> errors = new ArrayList<>();
        for (Map.Entry<String, Integer> occurrence : occurrences.entrySet()) {
            if (occurrence.getValue() > 1) {
                errors.add(new ConfigError(
                        FILE,
                        "stages[%s]".formatted(occurrence.getKey()),
                        "duplicate stage name '%s' declared %d times; stage names must be unique"
                                .formatted(occurrence.getKey(), occurrence.getValue())));
            }
        }
        return errors;
    }
}
