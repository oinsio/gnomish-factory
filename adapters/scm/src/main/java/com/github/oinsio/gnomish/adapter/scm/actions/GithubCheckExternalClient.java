package com.github.oinsio.gnomish.adapter.scm.actions;

import com.github.oinsio.gnomish.domain.engine.PollStatus;
import com.github.oinsio.gnomish.domain.engine.port.AttemptCommitWorkspace;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.githubapi.GithubConditionalRequestCache;
import com.github.oinsio.gnomish.githubapi.GithubHttpClient;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import java.time.InstantSource;

/**
 * The single-poll judgement of the {@code github-actions} built-in: reads the attempt commit of the
 * round under verification by narrowing the engine-supplied {@code Workspace} to the published
 * {@link AttemptCommitWorkspace} contract (FR26 of add-sandbox-core — the adapter-local workspace
 * stand-in is gone; FR1/FR3 of close-plugin-api-compilability-gap — the narrowed type is api
 * surface, so the adapter needs no application type), and polls
 * the configured {@code (owner, repo)} through {@link GithubWorkflowRunPoll} (run query, verdict
 * mapping, findings) for runs of exactly that commit.
 *
 * <p>Only {@link GithubHttpClient} (auth, retries), its {@link GithubConditionalRequestCache}
 * (ETag cache, an optimization only — NFR-C1), the configured repository coordinates and the
 * cannot-verify {@link RepeatSuppressor} (log-plane only — it decides how a repeated failure is
 * *worded*, never what this adapter answers) are held across calls; {@link GithubWorkflowRunQuery} and {@link GithubWorkflowJobsFetcher} are cheap
 * wrappers rebuilt fresh inside every {@link #poll}. No poll outcome, run identifier or verdict is
 * ever retained between calls: two independent instances polling the same {@code (owner, repo,
 * attemptCommitSha)} — e.g. before and after a crash-and-takeover — observe the same run set and
 * reach the same verdict without any state carried between them (NFR-R2); the ETag cache is a pure
 * performance optimization that a cold instance can simply do without.
 *
 * <p>{@link #runsOf} is the judgement the {@link GithubActionsExecutor} renders as the executor
 * contract's result; {@link #poll} renders it as one {@link PollStatus} for a check and a workspace.
 * Neither is an engine port: the engine reaches the built-in only through its registry entry (task
 * 5.2 of migrate-verify-to-executor-contract).
 *
 * <p>Implements NFR-R2 of add-external-check-github-actions; FR26 of add-sandbox-core; FR6 of
 * migrate-verify-to-executor-contract.
 */
public record GithubCheckExternalClient(
        GithubConditionalRequestCache cache, String owner, String repo, RepeatSuppressor pollSuppressor) {

    /**
     * @param httpClient the auth/retry-configured client for the GitHub (or GitHub-compatible)
     *     API this adapter targets; the token behind it is resolved by name through the {@code
     *     SecretsProvider} at run assembly ({@code ScmBuiltIns#actions}, FR26), over the
     *     endpoint the connection reader admitted
     * @param owner the repository owner the checks run in, from factory config
     * @param repo the repository name the checks run in, from factory config
     * @param clock the time source the cannot-verify suppressor measures on (FR18 of
     *     supervise-daemon-loops-and-embed-dashboard)
     */
    public GithubCheckExternalClient(GithubHttpClient httpClient, String owner, String repo, InstantSource clock) {
        this(new GithubConditionalRequestCache(httpClient), owner, repo, RepeatSuppressor.withDefaultRollUp(clock));
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
    public PollStatus poll(VerifyCheck.Executor check, Workspace workspace) {
        if (!(workspace instanceof AttemptCommitWorkspace attemptWorkspace)) {
            throw new IllegalArgumentException("GithubCheckExternalClient requires an AttemptCommitWorkspace, got: "
                    + (workspace == null ? "null" : workspace.getClass().getName()));
        }
        return runsOf(check.checkId(), attemptWorkspace.attemptCommitSha());
    }

    /**
     * Polls once for the {@code checkId} workflow's runs at {@code headSha}: the one judgement both
     * renderings share — this port's answer and the {@link GithubActionsExecutor}'s contract result
     * (FR6 of migrate-verify-to-executor-contract).
     *
     * @param checkId the workflow file path identifying the check
     * @param headSha the attempt commit under verification
     * @return the status this single poll observed; never null
     */
    public PollStatus runsOf(String checkId, String headSha) {
        GithubWorkflowRunPoll poll = new GithubWorkflowRunPoll(
                new GithubWorkflowRunQuery(cache, owner, repo),
                new GithubWorkflowJobsFetcher(cache, owner, repo),
                pollSuppressor);
        return poll.poll(checkId, headSha);
    }
}
