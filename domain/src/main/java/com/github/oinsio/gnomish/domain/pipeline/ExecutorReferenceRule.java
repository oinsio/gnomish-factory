package com.github.oinsio.gnomish.domain.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The pure cross-file rule over executor references (design D5 of define-executor-contract, phase
 * a): a stage's {@code executor.name} must name a declaration of the {@code config.yaml}
 * {@code executors:} section, and a {@code program} stage must name one — a program stage has no
 * other way to say which program it runs — and only a {@code program} stage may name one: the run's
 * dispatch keys every other stage by its type (design D8, task 7.3), so a name there would be an
 * executor the round's record names but that never ran it. No process is spawned and no describe is asked: the law
 * is bound before any execution environment exists.
 *
 * <p>Problems are located {@link ConfigError}s naming the stage manifest and the
 * {@code executor.name} field; an unknown name is quoted with the declared set. Nothing is thrown
 * (design D3 of load-pipeline-config). Error order is deterministic: stages in pipeline order.
 *
 * <p>Implements FR9, FR10 of define-executor-contract.
 */
public final class ExecutorReferenceRule {

    private static final String FIELD = "executor.name";

    private ExecutorReferenceRule() {}

    /**
     * Validates every stage's executor reference against the declared executors.
     *
     * <p>Implements FR9, FR10 of define-executor-contract.
     *
     * @param model the typed pipeline model; its stages in pipeline order
     * @return every located reference problem, in pipeline order; immutable, possibly empty
     */
    public static List<ConfigError> validate(PipelineDefinition model) {
        Set<String> declared = new LinkedHashSet<>();
        for (ExecutorDeclaration declaration : model.executors()) {
            declared.add(declaration.name());
        }
        List<ConfigError> errors = new ArrayList<>();
        for (StageDefinition stage : model.stages()) {
            String manifest = "stages/%s/stage.yaml".formatted(stage.name());
            String name = stage.executor().name();
            if (name == null) {
                if (stage.executor().type() == ExecutorType.PROGRAM) {
                    errors.add(
                            new ConfigError(
                                    manifest,
                                    FIELD,
                                    "missing required field 'executor.name'; a program stage names the executor declaration it runs"));
                }
            } else if (!declared.contains(name)) {
                errors.add(new ConfigError(manifest, FIELD, unknown(name, declared)));
            } else if (stage.executor().type() != ExecutorType.PROGRAM) {
                // Task 7.3: the run's dispatch keys every other stage by its type, so a name on one
                // would pin and report an executor that never ran its rounds (design D8).
                errors.add(new ConfigError(
                        manifest,
                        FIELD,
                        ("executor '%s' is a declared executor, which only a program stage runs;"
                                        + " declare executor.type program or drop executor.name")
                                .formatted(name)));
            }
        }
        return List.copyOf(errors);
    }

    private static String unknown(String name, Set<String> declared) {
        String known = declared.isEmpty()
                ? "config.yaml declares no executors"
                : "declared executors are " + String.join(", ", declared);
        return "unknown executor '%s'; %s".formatted(name, known);
    }
}
