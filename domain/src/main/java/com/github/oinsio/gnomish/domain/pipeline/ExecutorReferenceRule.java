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
 * <p>The rule is the one rule over an executor block's reference wherever the block is declared
 * (design D12 of extract-agent-round-runner): a stage's own block, and each {@code judge} check's —
 * a judge's {@code program} block names the declared executor that judges, and a judge's block of
 * another type names none.
 *
 * <p>Problems are located {@link ConfigError}s naming the stage manifest and the block's
 * {@code executor.name} field ({@code verify[<i>].executor.name} for a judge's); an unknown name is
 * quoted with the declared set. Nothing is thrown (design D3 of load-pipeline-config). Error order
 * is deterministic: stages in pipeline order, within a stage its own block first, then its judge
 * checks' in check order.
 *
 * <p>Implements FR9, FR10 of define-executor-contract; FR13, UX1 of extract-agent-round-runner.
 */
public final class ExecutorReferenceRule {

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
            validateBlock(manifest, MechanismFieldErrors.STAGE_BLOCK, "stage", stage.executor(), declared, errors);
            List<VerifyCheck> checks = stage.verify();
            for (int index = 0; index < checks.size(); index++) {
                if (checks.get(index) instanceof VerifyCheck.Judge judge) {
                    validateBlock(
                            manifest,
                            "verify[%d].executor".formatted(index),
                            "judge check",
                            judge.executor(),
                            declared,
                            errors);
                }
            }
        }
        return List.copyOf(errors);
    }

    private static void validateBlock(
            String manifest,
            String where,
            String useSite,
            StageDefinition.Executor executor,
            Set<String> declared,
            List<ConfigError> errors) {
        String field = where + ".name";
        String name = executor.name();
        if (name == null) {
            if (executor.type() == ExecutorType.PROGRAM) {
                errors.add(new ConfigError(
                        manifest,
                        field,
                        "missing required field '%s'; a program %s names the executor declaration it runs"
                                .formatted(field, useSite)));
            }
        } else if (!declared.contains(name)) {
            errors.add(new ConfigError(manifest, field, unknown(name, declared)));
        } else if (executor.type() != ExecutorType.PROGRAM) {
            // Task 7.3: the run's dispatch keys every other block by its type, so a name on one
            // would pin and report an executor that never ran its rounds (design D8).
            errors.add(new ConfigError(
                    manifest,
                    field,
                    ("executor '%s' is a declared executor, which only a program %s runs;"
                                    + " declare executor.type program or drop executor.name")
                            .formatted(name, useSite)));
        }
    }

    private static String unknown(String name, Set<String> declared) {
        String known = declared.isEmpty()
                ? "config.yaml declares no executors"
                : "declared executors are " + String.join(", ", declared);
        return "unknown executor '%s'; %s".formatted(name, known);
    }
}
