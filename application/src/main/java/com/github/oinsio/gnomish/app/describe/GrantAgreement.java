package com.github.oinsio.gnomish.app.describe;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.sandbox.CredentialGrantStanding;
import com.github.oinsio.gnomish.sandbox.EgressEntries;
import com.github.oinsio.gnomish.sandbox.ProgramCredentialGrant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Whether one executor declaration's credentials and egress hosts agree with its describe (design D5
 * of define-executor-contract, phase b; pipeline-config "Credentials and egress are checked against
 * describe"): every credential the law grants is one the describe declares and one that can reach
 * this executor's child alone ({@link ProgramCredentialGrant#standing}); every credential the
 * describe declares is granted, so no executor runs without its declared inputs; every egress host
 * the law grants is one the describe declares, compared in the one spelling the guard matches
 * ({@link EgressEntries#normalized}) — exactly, a wildcard matching only the same wildcard. Each
 * disagreement is a located {@link ConfigError}. Pure but for the grant's value lookup, which
 * returns no value.
 *
 * <p>The names quoted are inert: a law name passed the loader's variable grammar, a describe name
 * the wire contract's, and a describe host the host grammar ({@code ProgramDescribe}).
 *
 * <p>Implements FR4, NFR-S1 of define-executor-contract.
 */
final class GrantAgreement {

    private static final String CONFIG = "config.yaml";

    private GrantAgreement() {}

    /**
     * The located disagreements of {@code declaration}'s grants with {@code describe}.
     *
     * @param declaration the law's declaration; never null
     * @param describe the executor's protocol-1 describe answer; never null
     * @param grant the run's program credential grant; never null
     * @return credential errors, then egress errors, each in declaration order; immutable
     */
    static List<ConfigError> errors(ExecutorDeclaration declaration, Describe describe, ProgramCredentialGrant grant) {
        String name = declaration.name();
        String where = "executors." + name;
        List<ConfigError> errors = new ArrayList<>();
        List<String> granted = declaration.credentials();
        for (int i = 0; i < granted.size(); i++) {
            String credential = granted.get(i);
            String at = "%s.credentials[%d]".formatted(where, i);
            if (!describe.credentials().contains(credential)) {
                errors.add(new ConfigError(
                        CONFIG,
                        at,
                        "executor '%s' is granted credential '%s', which its describe does not declare; its describe declares %s"
                                .formatted(name, credential, listed(describe.credentials(), "no credential"))));
            }
            CredentialGrantStanding standing = grant.standing(credential);
            if (standing != CredentialGrantStanding.GRANTABLE) {
                errors.add(new ConfigError(
                        CONFIG,
                        at,
                        "executor '%s' is granted credential '%s', %s; a credential reaches only its executor"
                                .formatted(name, credential, why(standing))));
            }
        }
        for (String declared : describe.credentials()) {
            if (!granted.contains(declared)) {
                errors.add(new ConfigError(
                        CONFIG,
                        where + ".credentials",
                        "executor '%s' declares credential '%s' in its describe, which %s.credentials does not grant"
                                .formatted(name, declared, where)));
            }
        }
        Set<String> hosts =
                describe.egress().stream().map(EgressEntries::normalized).collect(Collectors.toSet());
        List<String> egress = declaration.egress();
        for (int i = 0; i < egress.size(); i++) {
            if (!hosts.contains(EgressEntries.normalized(egress.get(i)))) {
                errors.add(new ConfigError(
                        CONFIG,
                        "%s.egress[%d]".formatted(where, i),
                        "executor '%s' is granted egress host '%s', which its describe does not declare; its describe declares %s"
                                .formatted(
                                        name,
                                        EgressEntries.normalized(egress.get(i)),
                                        listed(describe.egress(), "no egress host"))));
            }
        }
        return List.copyOf(errors);
    }

    private static String why(CredentialGrantStanding standing) {
        return switch (standing) {
            case PASSED_THROUGH -> "which factory.sandbox.env-passthrough passes to every child";
            case WITHHELD -> "which the factory declares as its own credential and withholds from every child";
            case NO_VALUE -> "which has no value in the factory's secrets";
            case GRANTABLE -> throw new IllegalArgumentException("a grantable credential has no refusal");
        };
    }

    private static String listed(List<String> values, String none) {
        return values.isEmpty() ? none : String.join(", ", values);
    }
}
