package com.github.oinsio.gnomish.adapter.scm.status;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.github.oinsio.gnomish.domain.egress.EgressRefusedException;
import com.github.oinsio.gnomish.domain.egress.EndpointDeclaration;
import com.github.oinsio.gnomish.domain.engine.port.AttemptCommitWorkspace;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.executorcontract.Capability;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.EnforcementLevel;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.githubapi.GithubHttpException;
import com.github.oinsio.gnomish.httpegress.ExchangeLimitException;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code scm-status} built-in verdict executor (FR4, FR9, NFR-R2, NFR-S2 of
 * migrate-verify-to-executor-contract, design D3): reads the named verdicts the SCM holds for the
 * attempt SHA through {@link ScmStatusReader} and renders them through {@link ScmStatusResults}. It
 * is built over the check it judges — whose {@code expected} contexts it waits for — and the
 * check's working copy, as the other verdict built-ins are.
 *
 * <p>A {@code verdict} run reads the attempt commit the working copy carries; while an expected
 * context is absent or running, or the SCM answers with its rate limit, it is {@code pending}, its
 * key the attempt SHA, and a {@link #poll} of that key reads the same SHA again with no other state.
 * A read the SCM could not answer — a persistent server error, a rejection, a body that is no
 * listing, a refused hop, an outrun bound, a transport failure — is {@code failed/infrastructure}
 * naming why; an interrupt of the calling thread propagates as the stop it is.
 *
 * <p>The describe declares role {@code verdict}, the {@code poll} capability, settings that must be
 * empty (the contexts are the check's {@code expected}), the token's default name as its credential,
 * and as its {@code egress} the host of its endpoint only: the platform's statuses are served from
 * the API itself and redirect nowhere. Its connection is the operator's {@code
 * factory.check.scm-status}, read by {@code ScmConnectionReader}. The run mediates no model, so its
 * usage is a known zero (design D13).
 *
 * <p>Implements FR4, FR9, NFR-R2, NFR-S2, NFR-C1 of migrate-verify-to-executor-contract.
 */
public final class ScmStatusExecutor implements Executor {

    /** The built-in's name in the executor registry. */
    public static final String NAME = VerifyCheck.Executor.SCM_STATUS;

    /** {@code GNOMISH_SCM_STATUS_TOKEN}: the built-in's default credential name (NFR-S1, NFR-S2). */
    public static final String TOKEN_ENV_VAR = "GNOMISH_SCM_STATUS_TOKEN";

    /** The connection key naming the SCM's API endpoint, and GitHub's own API host. */
    public static final EndpointDeclaration ENDPOINT = new EndpointDeclaration("api-url", "api.github.com");

    private final ScmStatusReader statuses;
    private final VerifyCheck.Executor check;
    private final Workspace workspace;

    /**
     * @param statuses the reader over the admitted connection; never null
     * @param check the check this executor judges; its {@code expected} are the contexts; never null
     * @param workspace the check's working copy, carrying the attempt commit; never null
     */
    public ScmStatusExecutor(ScmStatusReader statuses, VerifyCheck.Executor check, Workspace workspace) {
        this.statuses = statuses;
        this.check = check;
        this.workspace = workspace;
    }

    @Override
    public Describe describe() {
        return describeAnswer(statuses);
    }

    /**
     * The describe answer of the built-in over {@code statuses}: what the executor registry entry
     * carries before any check is bound (design D18 of migrate-verify-to-executor-contract); its
     * egress is the one host the reader calls.
     *
     * @param statuses the reader over the admitted connection; never null
     * @return a fresh answer; never null
     */
    public static Describe describeAnswer(ScmStatusReader statuses) {
        return new Describe(
                Describe.SPOKEN_PROTOCOL_VERSION,
                UntrustedText.factory("built-in"),
                List.of(Role.VERDICT),
                List.of(Capability.POLL),
                JsonNodeFactory.instance.objectNode().put("type", "object").put("additionalProperties", false),
                List.of(TOKEN_ENV_VAR),
                List.of(statuses.endpointHost()),
                null,
                List.of(),
                EnforcementLevel.PROMPT_ONLY,
                true,
                false);
    }

    @Override
    public ExecutorResult start(ExecutorRequest request) {
        ExecutorResult refused = refusal(request);
        if (refused != null) {
            return refused;
        }
        if (!(workspace instanceof AttemptCommitWorkspace attempt)) {
            return ScmStatusResults.failed(UntrustedText.factory(
                    NAME + " reads the attempt commit from its working copy, and was given one that names none"));
        }
        return read(attempt.attemptCommitSha());
    }

    @Override
    public ExecutorResult poll(ExecutorRequest request, PendingKey key) {
        ExecutorResult refused = refusal(request);
        return refused != null ? refused : read(key.value());
    }

    private ExecutorResult read(String commit) {
        AttemptSha sha;
        try {
            sha = AttemptSha.of(commit);
        } catch (IllegalArgumentException e) {
            return ScmStatusResults.failed(UntrustedText.branchDocument(NAME + " reads " + e.getMessage()));
        }
        try {
            return switch (statuses.read(sha)) {
                case ScmStatusRead.Verdicts verdicts -> ScmStatusResults.of(verdicts.contexts(), check.expected(), sha);
                case ScmStatusRead.RateLimited limited -> ScmStatusResults.rateLimited(sha, limited);
            };
        } catch (ScmStatusReadException | GithubHttpException | ExchangeLimitException e) {
            return ScmStatusResults.failed(UntrustedText.tracker(String.valueOf(e.getMessage())));
        } catch (EgressRefusedException e) {
            // A refusal is the operator's configuration, never retried and never a condition of the
            // daemon (design D16, NG9): the reason names the refused host and the connection's key.
            return ScmStatusResults.failed(UntrustedText.tracker(e.refusal().describe()));
        }
    }

    /** The refusal of a request this built-in cannot answer, or {@code null} to answer it. */
    private static @Nullable ExecutorResult refusal(ExecutorRequest request) {
        if (request.role() != Role.VERDICT) {
            return ScmStatusResults.failed(
                    UntrustedText.factory(NAME + " answers verdict runs only, and was asked for a "
                            + request.role().wire() + " run"));
        }
        if (!request.settings().isEmpty()) {
            return ScmStatusResults.failed(UntrustedText.factory(NAME + " takes no settings, and was given "
                    + request.settings().size()));
        }
        return null;
    }
}
