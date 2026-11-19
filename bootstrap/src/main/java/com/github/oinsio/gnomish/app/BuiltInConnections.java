package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.scm.ScmConnection;
import com.github.oinsio.gnomish.adapter.scm.ScmConnectionReader;
import com.github.oinsio.gnomish.adapter.scm.ScmRepository;
import com.github.oinsio.gnomish.adapter.scm.builtin.ScmBuiltIns;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.domain.egress.EgressRefusedException;
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The run-assembly admission of the credentialed built-ins' connections (design D18 of
 * migrate-verify-to-executor-contract, "Where a refusal at run assembly goes"; design D16): once per
 * run, before the describe branch decides, the operator connection of each SCM built-in the law's
 * checks name is read and admitted through the one connection reader, its repository parsed and its
 * token's presence asked of the secrets port. Which built-ins a run needs is the law's decision, so
 * by ADR 0019 (decision 5) a connection the operator did not supply, or the guard refused, is the
 * task's precondition: a located {@link ConfigError} naming the check (its manifest and {@code
 * verify[<i>]}), the executor and the operator key — never an exception out of run assembly, never a
 * daemon condition. The first check naming a built-in carries its error; a later one adds none.
 *
 * <p>Each built-in's connection is read from its own key, {@code factory.check.<name>} — {@code
 * factory.check.github-actions} and {@code factory.check.scm-status} (task 5.2) — and this is the one
 * place a law's need of a connection is judged: no load rule refuses an unconnected built-in first
 * (design D18, "Where a refusal at run assembly goes").
 *
 * <p>The {@link SecretsProvider} is a member with no accessor (NFR-S1): it is asked only whether a
 * token resolves, and the token itself is not kept.
 *
 * <p>Implements FR13, FR17, NFR-S2 of migrate-verify-to-executor-contract.
 */
final class BuiltInConnections {

    private final Map<String, Map<String, Object>> subsections;
    private final ScmConnectionReader reader;
    private final SecretsProvider secrets;

    /**
     * @param subsections the operator's raw {@code factory.check.<key>} subsections; never null
     * @param reader the one reader of the built-ins' connections; never null
     * @param secrets the secrets port; never null
     */
    BuiltInConnections(
            Map<String, Map<String, Object>> subsections, ScmConnectionReader reader, SecretsProvider secrets) {
        this.subsections = Map.copyOf(subsections);
        this.reader = reader;
        this.secrets = secrets;
    }

    /**
     * Admits the connection of every SCM built-in {@code definition}'s checks name.
     *
     * @param definition the run's law; never null
     * @return the admitted connections by built-in name, or every located refusal; never null
     */
    Admission admit(PipelineDefinition definition) {
        Map<String, AdmittedConnection> admitted = new LinkedHashMap<>();
        List<ConfigError> errors = new ArrayList<>();
        List<String> judged = new ArrayList<>();
        for (StageDefinition stage : definition.stages()) {
            String manifest = "stages/%s/stage.yaml".formatted(stage.name());
            List<VerifyCheck> checks = stage.verify();
            for (int index = 0; index < checks.size(); index++) {
                if (checks.get(index) instanceof VerifyCheck.Executor check
                        && isScm(check.executorName())
                        && !judged.contains(check.executorName())) {
                    judged.add(check.executorName());
                    admitOne(check, manifest, "verify[%d]".formatted(index), admitted, errors);
                }
            }
        }
        return errors.isEmpty() ? new Admission.Admitted(admitted) : new Admission.Refused(errors);
    }

    private void admitOne(
            VerifyCheck.Executor check,
            String manifest,
            String where,
            Map<String, AdmittedConnection> admitted,
            List<ConfigError> errors) {
        String name = check.executorName();
        String operatorKey = ScmConnectionReader.PREFIX + name;
        ScmConnection connection;
        try {
            connection = ScmBuiltIns.admit(reader, name, subsections.getOrDefault(name, Map.of()));
            ScmRepository.of(operatorKey, connection.settings());
        } catch (IllegalArgumentException | EgressRefusedException e) {
            errors.add(refusal(manifest, where, check, operatorKey, String.valueOf(e.getMessage())));
            return;
        }
        String credential = ScmBuiltIns.credential(connection, name);
        if (secrets.find(credential).isEmpty()) {
            errors.add(refusal(
                    manifest,
                    where,
                    check,
                    operatorKey + ".credential",
                    "its token " + credential + " is missing or blank"));
            return;
        }
        admitted.put(name, new AdmittedConnection(connection.endpoint(), connection.settings()));
    }

    private static ConfigError refusal(
            String manifest, String where, VerifyCheck.Executor check, String key, String why) {
        return new ConfigError(
                manifest,
                where,
                "check '%s' names executor '%s', whose operator connection %s cannot be used: %s"
                        .formatted(check.checkId(), check.executorName(), key, why));
    }

    private static boolean isScm(String name) {
        return name.equals(BuiltInExecutors.GITHUB_ACTIONS) || name.equals(BuiltInExecutors.SCM_STATUS);
    }

    /** What the admission decided for a run's law. */
    sealed interface Admission {

        /**
         * Every connection the law's checks need, admitted.
         *
         * @param connections the admitted connections by built-in name; never null
         */
        record Admitted(Map<String, AdmittedConnection> connections) implements Admission {

            public Admitted {
                connections = Map.copyOf(connections);
            }
        }

        /**
         * At least one connection was refused.
         *
         * @param errors the located refusals; never empty
         */
        record Refused(List<ConfigError> errors) implements Admission {

            public Refused {
                errors = List.copyOf(errors);
            }
        }
    }
}
