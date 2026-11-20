package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Grades each check's settings with the params validator its built-in executor owns (FR6, FR13, UX1
 * of add-plugin-architecture; FR1, FR6, FR13 of migrate-verify-to-executor-contract): the {@code
 * http} and {@code github-actions} built-ins each grade their own per-check settings, which core
 * never interprets. The validators are keyed by built-in executor name and handed in by the
 * composition root; a check whose executor has no validator — the built-ins with no per-check
 * selectors, or a law's program — is graded by nothing here. A law's program is graded once its
 * describe answers: the describe preflight holds each check's settings to that executor's settings
 * schema ({@code SettingsAgreement} in {@code :application}).
 *
 * <p>Whether the executor a check names exists is not this seam's question: the law's load
 * validation ({@code ExecutorDeclarations}) refuses a name outside the six built-ins and the law's
 * declarations as a located error listing that union (design D18, "The six built-in names have one
 * owner"), so a missing key here means only "this executor grades no settings". The check-provider
 * registry this seam once read the discovered provider set from is gone (task 5.2).
 *
 * <p>Errors are located {@link ConfigError} data in stage-then-check order, never thrown, so they
 * aggregate with every other load problem in the loader's single pass (NFR-R1).
 *
 * <p>Implements FR6, FR13, UX1 of add-plugin-architecture; FR1, FR13 of
 * migrate-verify-to-executor-contract.
 */
public final class ExternalCheckSeamValidator {

    private ExternalCheckSeamValidator() {}

    /**
     * Grades the settings of every check whose executor owns a params validator.
     *
     * @param stages the mapped stages in pipeline order
     * @param validators the params validators keyed by built-in executor name; never null
     * @return every located problem, in stage-then-check-then-field order; empty when every graded
     *     check's settings are accepted
     */
    public static List<ConfigError> validate(
            List<StageDefinition> stages, Map<String, CheckSettingsValidator> validators) {
        List<ConfigError> errors = new ArrayList<>();
        for (StageDefinition stage : stages) {
            String manifest = "stages/%s/stage.yaml".formatted(stage.name());
            List<VerifyCheck> checks = stage.verify();
            for (int index = 0; index < checks.size(); index++) {
                if (!(checks.get(index) instanceof VerifyCheck.Executor check)) {
                    continue;
                }
                CheckSettingsValidator validator = validators.get(check.executorName());
                if (validator != null) {
                    String field = check.token() == VerifyCheck.Token.EXECUTOR ? "settings" : "params";
                    errors.addAll(
                            validator.validate(manifest, "verify[%d].%s".formatted(index, field), check.settings()));
                }
            }
        }
        return List.copyOf(errors);
    }
}
