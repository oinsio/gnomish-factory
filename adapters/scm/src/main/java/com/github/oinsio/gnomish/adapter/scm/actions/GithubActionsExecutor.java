package com.github.oinsio.gnomish.adapter.scm.actions;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.oinsio.gnomish.domain.egress.EndpointDeclaration;
import com.github.oinsio.gnomish.domain.engine.PollStatus;
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
import com.github.oinsio.gnomish.httpegress.EgressEntries;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code github-actions} built-in verdict executor (FR6, FR13 of
 * migrate-verify-to-executor-contract, design D4): the GitHub Actions check, moved out of the
 * vendor bundle into core, answering the executor contract. It is built over the check it judges
 * and the check's working copy, as the other verdict built-ins are, and over the runs judgement it
 * shares with the transitional external-check rendering ({@link GithubCheckExternalClient#runsOf}).
 *
 * <p>A {@code verdict} run polls the {@code checkId} workflow's runs at the attempt commit the
 * working copy carries: a run still in progress is {@code pending}, its key the attempt commit, and
 * a {@link #poll} of that key polls the same commit again with no other state (NFR-R2 of
 * add-external-check-github-actions); a finished run is {@code completed} with SARIF, one {@code
 * error} result per failed job, none for a green run, and the engine derives the verdict (design
 * D5); a run no verdict could be read from — a platform outage, a rejected token, a refused hop —
 * is {@code failed/infrastructure} naming why ({@link GithubActionsResults}).
 *
 * <p>The describe declares role {@code verdict}, the {@code poll} capability, settings that must be
 * empty — the check's selector is its {@code checkId}, so a manifest param is a mistake, as the
 * former params validator said — the token's default name as its credential, and as its {@code
 * egress} the hosts GitHub serves the API and the job logs from (design D16, "A redirect target is an
 * egress target"). Its connection is the operator's {@code factory.check.github-actions}, read by
 * {@code ScmConnectionReader}. The run mediates no model, so its usage is a known zero (design D13).
 * It declares {@code checkIdIsPinnedPath}: its {@code checkId} is the workflow file, which the
 * pin-check guard keeps byte-identical to the base branch with no pin path in the manifest (design
 * D17).
 *
 * <p>Implements FR6, FR13, FR16, FR17, NFR-C1 of migrate-verify-to-executor-contract.
 */
public final class GithubActionsExecutor implements Executor {

    /** The built-in's name in the executor registry, the one an {@code external} check maps to. */
    public static final String NAME = VerifyCheck.Executor.GITHUB_ACTIONS;

    /** {@code GNOMISH_GITHUB_ACTIONS_TOKEN}: the built-in's default credential name (NFR-S1). */
    public static final String TOKEN_ENV_VAR = "GNOMISH_GITHUB_ACTIONS_TOKEN";

    /** The connection key naming the platform's API endpoint, and GitHub's own API host. */
    public static final EndpointDeclaration ENDPOINT = new EndpointDeclaration("api-url", "api.github.com");

    /**
     * The hosts the built-in's calls reach on GitHub: the API, and the two hosts GitHub's own runner
     * documentation lists for job logs and artifacts, to which the API redirects a log download.
     */
    public static final List<String> EGRESS = List.of(
            "api.github.com",
            "results-receiver.actions.githubusercontent.com",
            EgressEntries.WILDCARD_PREFIX + "blob.core.windows.net");

    private final GithubCheckExternalClient runs;
    private final VerifyCheck.Executor check;
    private final Workspace workspace;

    /**
     * @param runs the runs judgement over the admitted connection; never null
     * @param check the check this executor judges; its {@code checkId} is the workflow file; never null
     * @param workspace the check's working copy, carrying the attempt commit; never null
     */
    public GithubActionsExecutor(GithubCheckExternalClient runs, VerifyCheck.Executor check, Workspace workspace) {
        this.runs = runs;
        this.check = check;
        this.workspace = workspace;
    }

    @Override
    public Describe describe() {
        return describeAnswer();
    }

    /**
     * The describe answer, the same for every binding: what the executor registry entry carries
     * before any check or round is bound (design D18 of migrate-verify-to-executor-contract).
     *
     * @return a fresh answer; never null
     */
    public static Describe describeAnswer() {
        return new Describe(
                Describe.SPOKEN_PROTOCOL_VERSION,
                UntrustedText.factory("built-in"),
                List.of(Role.VERDICT),
                List.of(Capability.POLL),
                settingsSchema(),
                List.of(TOKEN_ENV_VAR),
                EGRESS,
                null,
                List.of(),
                EnforcementLevel.PROMPT_ONLY,
                true,
                true);
    }

    @Override
    public ExecutorResult start(ExecutorRequest request) {
        ExecutorResult refused = refusal(request);
        if (refused != null) {
            return refused;
        }
        if (!(workspace instanceof AttemptCommitWorkspace attempt)) {
            return GithubActionsResults.failed(UntrustedText.factory(
                    NAME + " reads the attempt commit from its working copy, and was given one that names none"));
        }
        return poll(attempt.attemptCommitSha());
    }

    @Override
    public ExecutorResult poll(ExecutorRequest request, PendingKey key) {
        ExecutorResult refused = refusal(request);
        return refused != null ? refused : poll(key.value());
    }

    /** The settings schema: an object with no member, since the check's selector is its id. */
    static ObjectNode settingsSchema() {
        return JsonNodeFactory.instance.objectNode().put("type", "object").put("additionalProperties", false);
    }

    private ExecutorResult poll(String attemptCommit) {
        PollStatus status = runs.runsOf(check.checkId(), attemptCommit);
        return GithubActionsResults.of(status, attemptCommit);
    }

    /** The refusal of a request this built-in cannot answer, or {@code null} to answer it. */
    private static @Nullable ExecutorResult refusal(ExecutorRequest request) {
        if (request.role() != Role.VERDICT) {
            return GithubActionsResults.failed(
                    UntrustedText.factory(NAME + " answers verdict runs only, and was asked for a "
                            + request.role().wire() + " run"));
        }
        if (!request.settings().isEmpty()) {
            return GithubActionsResults.failed(UntrustedText.factory(NAME + " takes no settings, and was given "
                    + request.settings().size()));
        }
        return null;
    }
}
