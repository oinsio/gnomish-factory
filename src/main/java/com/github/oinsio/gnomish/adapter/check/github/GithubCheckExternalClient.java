package com.github.oinsio.gnomish.adapter.check.github;

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache;
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient;
import com.github.oinsio.gnomish.domain.engine.PollStatus;
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;

/**
 * The {@link ExternalCheckClient} adapter for GitHub Actions: downcasts the opaque {@link
 * Workspace} to {@link GithubCheckWorkspace} to learn the attempt commit and repository it must
 * poll, then delegates to {@link GithubWorkflowRunPoll} (task 3.1-4.2's run-query, verdict mapping
 * and findings) for the actual poll.
 *
 * <p>Only {@link GithubHttpClient} (auth, retries) and its {@link GithubConditionalRequestCache}
 * (ETag cache, an optimization only — NFR-C1) are held across calls; {@link
 * GithubWorkflowRunQuery} and {@link GithubWorkflowJobsFetcher} are cheap wrappers around {@code
 * (cache, owner, repo)} and are rebuilt fresh inside every {@link #poll} call from the workspace's
 * coordinates, since neither of the two existing plumbing classes carries any state of its own
 * beyond those three references. No poll outcome, run identifier or verdict is ever retained
 * between calls: two independent {@code GithubCheckExternalClient} instances polling the same
 * {@code (owner, repo, attemptCommitSha)} — e.g. before and after a crash-and-takeover — observe
 * the same run set and reach the same verdict without any state carried between them (NFR-R2);
 * the ETag cache is a pure performance optimization that a cold instance can simply do without.
 *
 * <p>Implements NFR-R2 of add-external-check-github-actions.
 */
public final class GithubCheckExternalClient implements ExternalCheckClient {

    private final GithubConditionalRequestCache cache;

    /**
     * @param httpClient the auth/retry-configured client for the GitHub (or GitHub-compatible)
     *     API this adapter targets; the token behind it is resolved via {@link GithubCheckToken}
     *     at wiring time (FR8)
     */
    public GithubCheckExternalClient(GithubHttpClient httpClient) {
        this.cache = new GithubConditionalRequestCache(httpClient);
    }

    /**
     * Polls once for {@code check.checkId()}'s runs at the attempt commit carried by {@code
     * workspace}.
     *
     * <p>Implements NFR-R2 of add-external-check-github-actions.
     *
     * @param check the external check to poll; {@code checkId} is the workflow file path (FR1)
     * @param workspace MUST be a {@link GithubCheckWorkspace}
     * @return the status this single poll observed; never null
     * @throws IllegalArgumentException if {@code workspace} is not a {@link GithubCheckWorkspace}
     */
    @Override
    public PollStatus poll(VerifyCheck.External check, Workspace workspace) {
        if (!(workspace instanceof GithubCheckWorkspace githubWorkspace)) {
            throw new IllegalArgumentException("GithubCheckExternalClient requires a GithubCheckWorkspace, got: "
                    + (workspace == null ? "null" : workspace.getClass().getName()));
        }
        GithubWorkflowRunPoll poll = new GithubWorkflowRunPoll(
                new GithubWorkflowRunQuery(cache, githubWorkspace.owner(), githubWorkspace.repo()),
                new GithubWorkflowJobsFetcher(cache, githubWorkspace.owner(), githubWorkspace.repo()));
        return poll.poll(check.checkId(), githubWorkspace.attemptCommitSha());
    }
}
