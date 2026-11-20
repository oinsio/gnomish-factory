package com.github.oinsio.gnomish.adapter.scm.builtin;

import com.github.oinsio.gnomish.adapter.scm.ScmConnection;
import com.github.oinsio.gnomish.adapter.scm.ScmConnectionReader;
import com.github.oinsio.gnomish.adapter.scm.ScmRepository;
import com.github.oinsio.gnomish.adapter.scm.actions.GithubActionsExecutor;
import com.github.oinsio.gnomish.adapter.scm.actions.GithubWorkflowRuns;
import com.github.oinsio.gnomish.adapter.scm.status.ScmStatusExecutor;
import com.github.oinsio.gnomish.adapter.scm.status.ScmStatusReader;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.githubapi.GithubCredential;
import com.github.oinsio.gnomish.githubapi.GithubHttpClient;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;

/**
 * The registry entries' bindings of the two credentialed SCM built-ins (design D18 of
 * migrate-verify-to-executor-contract, as amended 2026-11-10): the one production place each is
 * constructed ({@code ExecutorRegistryOwnerSpec}), and the one place run assembly reads their
 * connections through the one connection reader ({@link #admit}). Each is built at run assembly over
 * the connection admitted, with its token resolved through the secrets port, and binds a new
 * executor per check.
 *
 * <p>Implements FR1, FR6, FR13, NFR-S2 of migrate-verify-to-executor-contract.
 */
public final class ScmBuiltIns {

    private ScmBuiltIns() {}

    /**
     * Reads and admits the operator connection of the SCM built-in {@code executor} through the one
     * connection reader, from its own {@code factory.check.<executor>} subsection, with the
     * built-in's endpoint declaration and describe {@code egress}: the call run assembly makes once
     * per run for each SCM built-in the law's checks name (design D18).
     *
     * @param reader the one reader of the built-ins' connections; never null
     * @param executor {@code github-actions} or {@code scm-status}; never null
     * @param subsection the raw {@code factory.check.<executor>} subsection, before profile
     *     resolution; never null
     * @return the admitted connection; never null
     * @throws IllegalArgumentException when the subsection names no endpoint, or {@code executor} is
     *     not an SCM built-in
     * @throws com.github.oinsio.gnomish.domain.egress.EgressRefusedException when the guard refuses it
     */
    public static ScmConnection admit(ScmConnectionReader reader, String executor, Map<String, Object> subsection) {
        return switch (executor) {
            case GithubActionsExecutor.NAME ->
                reader.read(executor, subsection, GithubActionsExecutor.ENDPOINT, GithubActionsExecutor.EGRESS);
            case ScmStatusExecutor.NAME -> reader.read(executor, subsection, ScmStatusExecutor.ENDPOINT, List.of());
            default -> throw new IllegalArgumentException("'" + executor + "' is not an SCM built-in");
        };
    }

    /**
     * The name the token of {@code executor}'s connection resolves under: the connection's {@code
     * credential}, or the built-in's default.
     *
     * @param connection the admitted connection; never null
     * @param executor {@code github-actions} or {@code scm-status}; never null
     * @return the credential name; never null
     */
    public static String credential(ScmConnection connection, String executor) {
        return credential(executor, connection.settings());
    }

    /**
     * The name the token of {@code executor}'s connection resolves under, read off its resolved
     * subsection before any admission: the subsection's or its profile's {@code credential}, or the
     * built-in's default — the credential its describe declares (FR16, FR17 of
     * add-plugin-architecture). The same name {@link #credential(ScmConnection, String)} resolves the
     * token under, so the run's scrub and never-allowlist sets hold exactly the variable the
     * built-in reads (task 5.2 of migrate-verify-to-executor-contract).
     *
     * @param executor {@code github-actions} or {@code scm-status}; never null
     * @param settings the subsection with any {@code connection:} profile resolved into it; never null
     * @return the credential name; never null
     */
    public static String credential(String executor, Map<String, Object> settings) {
        return GithubCredential.nameOr(
                settings,
                executor.equals(GithubActionsExecutor.NAME)
                        ? GithubActionsExecutor.TOKEN_ENV_VAR
                        : ScmStatusExecutor.TOKEN_ENV_VAR);
    }

    /**
     * The {@code github-actions} built-in over its admitted connection.
     *
     * @param connection the connection run assembly admitted; never null
     * @param secrets the secrets port its token resolves through; never null
     * @param clock the read-time source the runs judgement stamps on; never null
     * @return the entry's binding; never null
     * @throws IllegalArgumentException when the connection names no {@code owner/name}
     * @throws com.github.oinsio.gnomish.adapter.scm.ScmTokenException when the token does not resolve
     */
    public static ScmBuiltIn actions(ScmConnection connection, SecretsProvider secrets, InstantSource clock) {
        ScmRepository repository =
                ScmRepository.of(ScmConnectionReader.PREFIX + GithubActionsExecutor.NAME, connection.settings());
        var runs = new GithubWorkflowRuns(
                new GithubHttpClient(
                        connection.endpoint(), connection.token(secrets, GithubActionsExecutor.TOKEN_ENV_VAR)),
                repository.owner(),
                repository.name(),
                clock);
        return new Actions(runs);
    }

    /**
     * The {@code scm-status} built-in over its admitted connection.
     *
     * @param connection the connection run assembly admitted; never null
     * @param secrets the secrets port its token resolves through; never null
     * @return the entry's binding; never null
     * @throws IllegalArgumentException when the connection names no {@code owner/name}
     * @throws com.github.oinsio.gnomish.adapter.scm.ScmTokenException when the token does not resolve
     */
    public static ScmBuiltIn statuses(ScmConnection connection, SecretsProvider secrets) {
        return new Statuses(ScmStatusReader.connect(connection, secrets));
    }

    private record Actions(GithubWorkflowRuns runs) implements ScmBuiltIn {

        @Override
        public Describe describe() {
            return GithubActionsExecutor.describeAnswer();
        }

        @Override
        public Executor bind(VerifyCheck.Executor check, Workspace workspace) {
            return new GithubActionsExecutor(runs, check, workspace);
        }
    }

    private record Statuses(ScmStatusReader reader) implements ScmBuiltIn {

        @Override
        public Describe describe() {
            return ScmStatusExecutor.describeAnswer(reader);
        }

        @Override
        public Executor bind(VerifyCheck.Executor check, Workspace workspace) {
            return new ScmStatusExecutor(reader, check, workspace);
        }
    }
}
