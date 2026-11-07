package com.github.oinsio.gnomish.domain.pipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The pure rules over what a verify check asks of its verdict executor that the law alone decides
 * (design D3, D6 of migrate-verify-to-executor-contract):
 *
 * <ol>
 *   <li>an {@code scm-status} check declares a non-empty {@code expected} list — the context names
 *       it waits for, the only cure for "pending forever" (FR4);
 *   <li>an {@code external} or {@code executor:} check naming a law executor granted credentials
 *       does not run in the round box: {@code same-box} would hand the token to the gnome's
 *       environment (NFR-S1). The loader defaults such a check to {@code fresh-box}, so a {@code
 *       same-box} here is the author's own declaration. The law's grant stands for the describe's
 *       credentials: the describe preflight refuses a grant that differs from them
 *       ({@code GrantAgreement}), so no run reaches a check under a grant its executor disowns. A
 *       {@code builtin} or {@code command} check names a built-in, which a law never declares, so it
 *       is out of scope; a built-in verdict executor's own token enters no box at all (NFR-S1).
 * </ol>
 *
 * <p>Whether the named executor serves role {@code verdict} is not judged here: a program's roles
 * are its describe's, which exists only once the task's execution environment is live, so the
 * describe preflight judges it ({@code DescribeAgreement}, the check use site).
 *
 * <p>Problems are located {@link ConfigError}s naming the stage manifest and the check's field;
 * nothing is thrown. Order: stages in pipeline order, checks in check order.
 *
 * <p>Implements FR4, NFR-S1 of migrate-verify-to-executor-contract.
 */
public final class VerdictCheckRule {

    private VerdictCheckRule() {}

    /**
     * Validates every verify check of every stage.
     *
     * @param model the typed pipeline model
     * @return every located problem; immutable, possibly empty
     */
    public static List<ConfigError> validate(PipelineDefinition model) {
        Map<String, List<String>> grants = new HashMap<>();
        for (ExecutorDeclaration declaration : model.executors()) {
            grants.put(declaration.name(), declaration.credentials());
        }
        List<ConfigError> errors = new ArrayList<>();
        for (StageDefinition stage : model.stages()) {
            String manifest = "stages/%s/stage.yaml".formatted(stage.name());
            List<VerifyCheck> checks = stage.verify();
            for (int index = 0; index < checks.size(); index++) {
                if (checks.get(index) instanceof VerifyCheck.Executor check) {
                    validateCheck(manifest, index, check, grants, errors);
                }
            }
        }
        return List.copyOf(errors);
    }

    private static void validateCheck(
            String manifest,
            int index,
            VerifyCheck.Executor check,
            Map<String, List<String>> grants,
            List<ConfigError> errors) {
        if (check.executorName().equals(VerifyCheck.Executor.SCM_STATUS)
                && check.expected().isEmpty()) {
            errors.add(new ConfigError(
                    manifest,
                    "verify[%d].expected".formatted(index),
                    ("scm-status check '%s' declares no expected contexts;"
                                    + " list the context names it waits for under 'expected'")
                            .formatted(check.checkId())));
        }
        List<String> granted = grants.getOrDefault(check.executorName(), List.of());
        boolean lawNamed = check.token() == VerifyCheck.Token.EXTERNAL || check.token() == VerifyCheck.Token.EXECUTOR;
        if (lawNamed && !granted.isEmpty() && check.verifyIn() == VerifyCheck.VerifyIn.SAME_BOX) {
            errors.add(new ConfigError(
                    manifest,
                    "verify[%d].verifyIn".formatted(index),
                    ("check '%s' names executor '%s', which is granted credentials %s;"
                                    + " a credentialed check runs in a fresh box: declare verify-in fresh-box or omit it")
                            .formatted(check.checkId(), check.executorName(), String.join(", ", granted))));
        }
    }
}
