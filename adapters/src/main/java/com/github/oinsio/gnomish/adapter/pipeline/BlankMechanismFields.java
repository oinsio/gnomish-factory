package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.MechanismFieldErrors;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The wire check of a Mechanism field declared blank where the declaration itself is the fault
 * (FR2, UX2 of add-command-executor; design D3): a {@code model} on a {@code command} stage, and a
 * {@code command} on an {@code api}, {@code agent-cli} or {@code program} stage. The mapping erases
 * both — a blank {@code model} reaches the typed model as {@code ""}, the same value as an absent
 * one, and a blank {@code command} builds no {@code CommandLine} — so the domain rule, which owns
 * every other case of the pair, cannot see that the field was declared at all.
 *
 * <p>Only those two cases are reported here, through the domain's own {@link MechanismFieldErrors},
 * so one fault is one error with one sentence whichever layer saw it. A blank {@code command} on a
 * {@code command} stage maps to no command and is the domain's missing-command error; a non-blank
 * field reaches the domain and is its to judge. An unknown or missing {@code type} is
 * {@link StructuralValidation}'s error, and nothing is checked against it.
 *
 * <p>Implements FR1, FR2, UX2 of add-command-executor.
 */
final class BlankMechanismFields {

    private static final String COMMAND = "command";
    /** Every known executor token but {@code command}, from the structural allowlist, never a second list. */
    private static final List<String> OTHER_TYPES = StructuralValidation.EXECUTOR_TYPES.stream()
            .filter(type -> !COMMAND.equals(type))
            .toList();

    private BlankMechanismFields() {}

    static void check(List<ConfigError> errors, String file, @Nullable ExecutorDto executor) {
        if (executor == null) {
            return;
        }
        String type = executor.type();
        if (type == null) {
            return;
        }
        if (COMMAND.equals(type) && declaredBlank(executor.model())) {
            errors.add(MechanismFieldErrors.modelOnCommandStage(file));
        }
        if (OTHER_TYPES.contains(type) && declaredBlank(executor.command())) {
            errors.add(MechanismFieldErrors.commandOnStage(file, type));
        }
    }

    private static boolean declaredBlank(@Nullable String value) {
        return value != null && value.isBlank();
    }
}
