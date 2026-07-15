package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Structural shape validation of an already-parsed DTO (task 5.2, FR5): the
 * required fields the DTO&rarr;domain mapper (task 5.3) needs, and the two
 * raw-string enums Jackson accepts verbatim rather than rejecting during parse
 * — the executor {@code type} and the {@code advancement} mode. Because every
 * wire field is nullable (design D2), a missing required field deserializes to
 * {@code null}; an unknown enum value arrives as a raw string. Both become
 * located {@link ConfigError}s here (NFR-O1, UX2), so the mapper only ever sees
 * a structurally acceptable DTO.
 *
 * <p>Boundary — what is deliberately NOT checked here (avoiding double-reporting
 * with the pure domain rules that already own these, design D6):
 *
 * <ul>
 *   <li>{@code config.yaml} {@code schemaVersion} presence &mdash;
 *       {@code SchemaVersionRule} (task 4.1);</li>
 *   <li>an empty {@code pipeline.yaml} stage list &mdash; {@code StageOrderRule}
 *       (task 4.2); only an <em>absent</em> {@code stages} key (a shape problem)
 *       is caught here;</li>
 *   <li>a blank executor/judge {@code model} and {@code external}/{@code judge}
 *       value ranges &mdash; {@code StageSanityRule} (task 4.4);</li>
 *   <li>artifact-id uniqueness and reference resolution &mdash;
 *       {@code ArtifactGraphRule} (task 4.3); only a <em>missing</em> output
 *       {@code id} or internal {@code producerOutputId} (both of which the graph
 *       rule assumes present) is caught here;</li>
 *   <li>referenced-file existence &mdash; the loader's I/O checks (task 6.3).</li>
 * </ul>
 *
 * <p>All shape problems of one file are reported in a single pass (FR8, UX1),
 * in declaration order (NFR-R1). The verify checks and inputs/outputs sections
 * are optional at this layer: an absent section is not a structural problem.
 *
 * <p>Implements FR5 of load-pipeline-config.
 */
public final class StructuralValidation {

    private static final String PIPELINE = "pipeline.yaml";

    /** The known executor wire values (the domain {@code ExecutorType} enum). */
    private static final List<String> EXECUTOR_TYPES = List.of("api", "agent-cli");

    /** The known advancement wire values (the domain {@code AdvancementMode} enum). */
    private static final List<String> ADVANCEMENT_MODES = List.of("auto", "manual");

    private StructuralValidation() {}

    /**
     * Checks {@code pipeline.yaml} shape: the {@code stages} key must be present
     * (non-null). An empty list is left to {@code StageOrderRule} (task 4.2).
     *
     * <p>Implements FR5 of load-pipeline-config.
     *
     * @param pipeline the parsed {@code pipeline.yaml} DTO
     * @return one located error when {@code stages} is absent, else empty
     */
    public static List<ConfigError> checkPipeline(PipelineDto pipeline) {
        if (pipeline.stages() == null) {
            return List.of(new ConfigError(PIPELINE, "stages", "missing required field 'stages'"));
        }
        return List.of();
    }

    /**
     * Checks one {@code stage.yaml} manifest's shape: the required
     * scalar sections ({@code purpose}, {@code executor} with its {@code type},
     * {@code instructions}, {@code advancement}), the two raw-string enums, and
     * the per-element required ids of any declared outputs/internal inputs.
     *
     * <p>Implements FR5 of load-pipeline-config.
     *
     * @param file the manifest path relative to the {@code .gnomish/} root
     * @param stage the parsed {@code stage.yaml} DTO
     * @return every shape problem of this stage, in declaration order; immutable
     */
    public static List<ConfigError> checkStage(String file, StageDto stage) {
        List<ConfigError> errors = new ArrayList<>();
        requirePresent(errors, file, "purpose", stage.purpose());
        checkInputs(errors, file, stage.inputs());
        checkOutputs(errors, file, stage.outputs());
        checkExecutor(errors, file, stage.executor());
        requirePresent(errors, file, "instructions", stage.instructions());
        checkAdvancement(errors, file, stage.advancement());
        return List.copyOf(errors);
    }

    private static void checkInputs(List<ConfigError> errors, String file, @Nullable List<ArtifactInputDto> inputs) {
        if (inputs == null) {
            return;
        }
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i) instanceof ArtifactInputDto.Internal internal && internal.producerOutputId() == null) {
                errors.add(new ConfigError(
                        file, "inputs[%d].producerOutputId".formatted(i), "missing required field 'producerOutputId'"));
            }
        }
    }

    private static void checkOutputs(List<ConfigError> errors, String file, @Nullable List<ArtifactOutputDto> outputs) {
        if (outputs == null) {
            return;
        }
        for (int i = 0; i < outputs.size(); i++) {
            if (outputs.get(i).id() == null) {
                errors.add(new ConfigError(file, "outputs[%d].id".formatted(i), "missing required field 'id'"));
            }
        }
    }

    private static void checkExecutor(List<ConfigError> errors, String file, @Nullable ExecutorDto executor) {
        if (executor == null) {
            errors.add(new ConfigError(file, "executor", "missing required field 'executor'"));
            return;
        }
        String type = executor.type();
        if (type == null) {
            errors.add(new ConfigError(file, "executor.type", "missing required field 'executor.type'"));
            return;
        }
        if (!EXECUTOR_TYPES.contains(type)) {
            errors.add(new ConfigError(
                    file,
                    "executor.type",
                    "unknown executor '%s'; known executors are %s".formatted(type, join(EXECUTOR_TYPES))));
        }
    }

    private static void checkAdvancement(List<ConfigError> errors, String file, @Nullable String advancement) {
        if (advancement == null) {
            errors.add(new ConfigError(file, "advancement", "missing required field 'advancement'"));
            return;
        }
        if (!ADVANCEMENT_MODES.contains(advancement)) {
            errors.add(new ConfigError(
                    file,
                    "advancement",
                    "unknown advancement '%s'; known modes are %s".formatted(advancement, join(ADVANCEMENT_MODES))));
        }
    }

    private static void requirePresent(List<ConfigError> errors, String file, String field, @Nullable String value) {
        if (value == null) {
            errors.add(new ConfigError(file, field, "missing required field '%s'".formatted(field)));
        }
    }

    private static String join(List<String> values) {
        return String.join(", ", values);
    }
}
