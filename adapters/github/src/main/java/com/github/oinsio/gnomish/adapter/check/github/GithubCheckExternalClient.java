package com.github.oinsio.gnomish.adapter.check.github;

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache;
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient;
import com.github.oinsio.gnomish.app.port.check.AttemptCommitWorkspace;
import com.github.oinsio.gnomish.domain.engine.PollStatus;
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;

/**
 * The {@link ExternalCheckClient} adapter for GitHub Actions: reads the attempt commit of the
 * round under verification by narrowing the engine-supplied {@code Workspace} to the published
 * {@link AttemptCommitWorkspace} contract (FR26 of add-sandbox-core — the adapter-local workspace
 * stand-in is gone; FR1/FR3 of close-plugin-api-compilability-gap — the narrowed type is api
 * surface, so this vendor bundle compiles against {@code gnomish-plugin-api} alone), and polls
 * the configured {@code (owner, repo)} through {@link GithubWorkflowRunPoll} (run query, verdict
 * mapping, findings) for runs of exactly that commit.
 *
 * <p>Only {@link GithubHttpClient} (auth, retries), its {@link GithubConditionalRequestCache}
 * (ETag cache, an optimization only — NFR-C1), and the configured repository coordinates are held
 * across calls; {@link GithubWorkflowRunQuery} and {@link GithubWorkflowJobsFetcher} are cheap
 * wrappers rebuilt fresh inside every {@link #poll}. No poll outcome, run identifier or verdict is
 * ever retained between calls: two independent instances polling the same {@code (owner, repo,
 * attemptCommitSha)} — e.g. before and after a crash-and-takeover — observe the same run set and
 * reach the same verdict without any state carried between them (NFR-R2); the ETag cache is a pure
 * performance optimization that a cold instance can simply do without.
 *
 * <p>Implements NFR-R2 of add-external-check-github-actions; FR26 of add-sandbox-core.
 */
public record GithubCheckExternalClient(GithubConditionalRequestCache cache, String owner, String repo)
        implements ExternalCheckClient {

    /**
     * @param httpClient the auth/retry-configured client for the GitHub (or GitHub-compatible)
     *     API this adapter targets; the token behind it is resolved by name through the {@code
     *     SecretsProvider} at wiring time ({@link GithubCheckClientFactory}, FR26)
     * @param owner the repository owner the checks run in, from factory config
     * @param repo the repository name the checks run in, from factory config
     */
    public GithubCheckExternalClient(GithubHttpClient httpClient, String owner, String repo) {
        this(new GithubConditionalRequestCache(httpClient), owner, repo);
    }

    /**
     * Polls once for {@code check.checkId()}'s runs at the attempt commit carried by {@code
     * workspace}.
     *
     * <p>Implements NFR-R2 of add-external-check-github-actions; FR26 of add-sandbox-core; FR1
     * of close-plugin-api-compilability-gap.
     *
     * @param check the external check to poll; {@code checkId} is the workflow file path (FR1)
     * @param workspace MUST be an {@link AttemptCommitWorkspace}
     * @return the status this single poll observed; never null
     * @throws IllegalArgumentException if {@code workspace} is not an {@link
     *     AttemptCommitWorkspace} — caught by the verify orchestrator and classified as
     *     CannotVerify, never a silent pass
     */
    @Override
    public PollStatus poll(VerifyCheck.External check, Workspace workspace) {
        if (!(workspace instanceof AttemptCommitWorkspace attemptWorkspace)) {
            throw new IllegalArgumentException("GithubCheckExternalClient requires an AttemptCommitWorkspace, got: "
                    + (workspace == null ? "null" : workspace.getClass().getName()));
        }
        GithubWorkflowRunPoll poll = new GithubWorkflowRunPoll(
                new GithubWorkflowRunQuery(cache, owner, repo), new GithubWorkflowJobsFetcher(cache, owner, repo));
        return poll.poll(check.checkId(), attemptWorkspace.attemptCommitSha());
    }
}
