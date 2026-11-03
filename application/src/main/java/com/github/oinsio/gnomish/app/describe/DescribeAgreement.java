package com.github.oinsio.gnomish.app.describe;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.sandbox.ProgramCredentialGrant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Whether the describe answers agree with the law (design D5 of define-executor-contract, phase b):
 * each executor speaks the factory's protocol version, its declaration's settings satisfy the
 * schema its describe declares, as each stage sends them ({@link SettingsAgreement}), its credentials and egress hosts agree
 * with its describe ({@link GrantAgreement}), and it serves the role each use site
 * needs — a stage needs {@code transform}; the law has no role field, the use site implies it. A
 * describe that lists only {@code effect} is refused with that role named as reserved (NG4). A
 * check would need {@code verdict}, but no check names an executor until
 * {@code migrate-verify-to-executor-contract} (NG1) gives the verify list that reference; that change
 * adds the check use site here. Each disagreement is a located {@link ConfigError}, as a law error
 * found at load is. Pure.
 *
 * <p>Implements FR1, FR4, FR9, FR13, NFR-S1, UX1, NG4 of define-executor-contract.
 */
final class DescribeAgreement {

    private static final String CONFIG = "config.yaml";

    private DescribeAgreement() {}

    /**
     * The disagreements between {@code definition} and the answers.
     *
     * @param definition the law; never null
     * @param answers the describe answer per described executor name; a declaration with no answer
     *     (its program could not answer) is judged no further; never null
     * @param grant the run's program credential grant, which judges each granted name; never null
     * @return every located disagreement, declarations first, then stages in pipeline order;
     *     immutable, possibly empty
     */
    static List<ConfigError> errors(
            PipelineDefinition definition, Map<String, Describe> answers, ProgramCredentialGrant grant) {
        List<ConfigError> errors = new ArrayList<>();
        for (ExecutorDeclaration declaration : definition.executors()) {
            String name = declaration.name();
            Describe describe = answers.get(name);
            if (describe == null) {
                continue;
            }
            if (describe.protocolVersion() != Describe.SPOKEN_PROTOCOL_VERSION) {
                // An answer in another protocol is read no further: its settings schema is that
                // protocol's, not one this factory can hold the law's settings to.
                errors.add(new ConfigError(
                        CONFIG,
                        "executors." + name,
                        "executor '%s' speaks executor protocol version %d; this factory speaks version %d"
                                .formatted(name, describe.protocolVersion(), Describe.SPOKEN_PROTOCOL_VERSION)));
            } else {
                errors.addAll(SettingsAgreement.errors(definition, declaration, describe));
                errors.addAll(GrantAgreement.errors(declaration, describe, grant));
            }
        }
        for (StageDefinition stage : definition.stages()) {
            String name = stage.executor().name();
            if (name == null) {
                continue;
            }
            Describe describe = answers.get(name);
            if (describe != null && !describe.roles().contains(Role.TRANSFORM)) {
                errors.add(new ConfigError(
                        "stages/%s/stage.yaml".formatted(stage.name()),
                        "executor.name",
                        unserved(name, "stage '%s'".formatted(stage.name()), Role.TRANSFORM, describe)));
            }
        }
        return List.copyOf(errors);
    }

    /**
     * Why {@code describe} does not serve the role {@code useSite} needs. A describe that lists only
     * {@code effect} names that role as reserved: the vocabulary exists, but this version executes
     * no {@code effect} run (NG4), so the operator learns the executor is not misdeclared but early.
     */
    private static String unserved(String name, String useSite, Role needed, Describe describe) {
        if (Set.copyOf(describe.roles()).equals(Set.of(Role.EFFECT))) {
            return "executor '%s' lists only role '%s', which is reserved and not executed in this version; %s needs role '%s'"
                    .formatted(name, Role.EFFECT.wire(), useSite, needed.wire());
        }
        return "executor '%s' does not serve role '%s', which %s needs; its describe lists %s"
                .formatted(name, needed.wire(), useSite, roles(describe));
    }

    private static String roles(Describe describe) {
        return describe.roles().isEmpty()
                ? "no role"
                : describe.roles().stream().map(Role::wire).collect(Collectors.joining(", "));
    }
}
