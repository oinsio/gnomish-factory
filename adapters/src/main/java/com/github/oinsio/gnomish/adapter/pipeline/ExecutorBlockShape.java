package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The structural shape of an executor block (task 5.2, FR5 of load-pipeline-config), wherever the
 * block is declared — a stage's own {@code executor:} and each {@code judge} check's (design D12 of
 * extract-agent-round-runner: one block, judged by the same rules): its raw {@code type} against the
 * known executor tokens, the blank Mechanism fields ({@link BlankMechanismFields}) and the
 * tighten-only {@code sandbox}, each located under the block's own location. Called by {@link
 * StructuralValidation}, which owns the per-file error order; a missing stage block is its error,
 * a missing judge block the mapper's.
 *
 * <p>Implements FR5 of load-pipeline-config; FR14 of add-sandbox-core (the tighten-only sandbox
 * check); FR13, UX1 of extract-agent-round-runner.
 */
final class ExecutorBlockShape {

    /** The known executor wire values (the domain {@code ExecutorType} enum); {@link BlankMechanismFields} reads them. */
    static final List<String> EXECUTOR_TYPES = List.of("api", "agent-cli", "program", "command");

    private ExecutorBlockShape() {}

    /**
     * The shape of one declared executor block, wherever it is declared (design D12 of
     * extract-agent-round-runner): its {@code type}, the blank Mechanism fields and the
     * tighten-only {@code sandbox}, each located under {@code where}.
     */
    static void check(List<ConfigError> errors, String file, String where, ExecutorDto executor) {
        checkType(errors, file, where, executor);
        BlankMechanismFields.check(errors, file, where, executor);
        checkSandbox(errors, file, where, executor);
    }

    /**
     * Each {@code judge} check's declared executor block gets the block's shape checks at {@code
     * verify[<i>].executor}; an absent one is the mapper's located error, since no judge is
     * defaulted ({@link StageDefinitionMapper#mapJudgeExecutor}).
     */
    static void checkJudges(List<ConfigError> errors, String file, @Nullable List<VerifyCheckDto> verify) {
        if (verify == null) {
            return;
        }
        for (int i = 0; i < verify.size(); i++) {
            if (verify.get(i) instanceof VerifyCheckDto.Judge judge && judge.executor() != null) {
                check(errors, file, "verify[%d].executor".formatted(i), judge.executor());
            }
        }
    }

    private static void checkType(List<ConfigError> errors, String file, String where, ExecutorDto executor) {
        String field = where + ".type";
        String type = executor.type();
        if (type == null) {
            errors.add(new ConfigError(file, field, "missing required field '%s'".formatted(field)));
            return;
        }
        if (!EXECUTOR_TYPES.contains(type)) {
            errors.add(new ConfigError(
                    file,
                    field,
                    "unknown executor '%s'; known executors are %s"
                            .formatted(type, String.join(", ", EXECUTOR_TYPES))));
        }
    }

    /**
     * Tighten-only enforcement of the Mechanism's {@code sandbox} block (FR14 of
     * add-sandbox-core): a repo may declare needs and {@code requiresFresh}
     * (both tighten), but never a {@code binding} — naming a concrete adapter
     * ({@code container}, …) or requesting host execution ({@code host}) is an
     * operator-only decision that lives solely in factory installation config.
     * A declared {@code binding} is therefore a located error, so the manifest
     * cannot weaken its own isolation. Any other weakening spelling is already
     * closed by Jackson's unknown-field capture ({@link StructuralParse}). An
     * absent {@code sandbox} or {@code binding} is the correct, tightening-only
     * form and yields nothing.
     */
    private static void checkSandbox(List<ConfigError> errors, String file, String where, ExecutorDto executor) {
        SandboxDto sandbox = executor.sandbox();
        if (sandbox == null) {
            return;
        }
        String binding = sandbox.binding();
        if (binding != null) {
            errors.add(new ConfigError(
                    file,
                    where + ".sandbox.binding",
                    "repo may not declare a sandbox binding '%s'; adapter binding and any weakening live only in factory installation config (a stage manifest may only tighten)"
                            .formatted(binding)));
        }
    }
}
