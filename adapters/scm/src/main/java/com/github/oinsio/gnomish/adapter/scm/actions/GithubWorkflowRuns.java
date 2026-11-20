package com.github.oinsio.gnomish.adapter.scm.actions;

import com.github.oinsio.gnomish.domain.engine.PollStatus;
import com.github.oinsio.gnomish.githubapi.GithubConditionalRequestCache;
import com.github.oinsio.gnomish.githubapi.GithubHttpClient;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import java.time.InstantSource;

/**
 * The runs judgement of the {@code github-actions} built-in: one poll of the configured {@code
 * (owner, repo)} for the runs of one workflow at one commit, through {@link GithubWorkflowRunPoll}
 * (run query, verdict mapping, findings). The {@link GithubActionsExecutor} reads the attempt
 * commit from its working copy (or from the pending key on a re-poll) and renders this judgement as
 * the executor contract's result (FR6 of migrate-verify-to-executor-contract).
 *
 * <p>Only {@link GithubHttpClient} (auth, retries), its {@link GithubConditionalRequestCache}
 * (ETag cache, an optimization only — NFR-C1), the configured repository coordinates and the
 * cannot-verify {@link RepeatSuppressor} (log-plane only — it decides how a repeated failure is
 * *worded*, never what this judgement answers) are held across calls; {@link
 * GithubWorkflowRunQuery} and {@link GithubWorkflowJobsFetcher} are cheap wrappers rebuilt fresh
 * inside every {@link #runsOf}. No poll outcome, run identifier or verdict is ever retained between
 * calls: two independent instances polling the same {@code (owner, repo, commit)} — e.g. before and
 * after a crash-and-takeover — observe the same run set and reach the same verdict without any
 * state carried between them (NFR-R2); the ETag cache is a pure performance optimization that a
 * cold instance can simply do without.
 *
 * <p>Implements NFR-R2 of add-external-check-github-actions; FR6 of
 * migrate-verify-to-executor-contract.
 */
public record GithubWorkflowRuns(
        GithubConditionalRequestCache cache, String owner, String repo, RepeatSuppressor pollSuppressor) {

    /**
     * @param httpClient the auth/retry-configured client for the GitHub (or GitHub-compatible)
     *     API this judgement targets; the token behind it is resolved by name through the {@code
     *     SecretsProvider} at run assembly ({@code ScmBuiltIns#actions}, FR26 of add-sandbox-core),
     *     over the endpoint the connection reader admitted
     * @param owner the repository owner the checks run in, from factory config
     * @param repo the repository name the checks run in, from factory config
     * @param clock the time source the cannot-verify suppressor measures on (FR18 of
     *     supervise-daemon-loops-and-embed-dashboard)
     */
    public GithubWorkflowRuns(GithubHttpClient httpClient, String owner, String repo, InstantSource clock) {
        this(new GithubConditionalRequestCache(httpClient), owner, repo, RepeatSuppressor.withDefaultRollUp(clock));
    }

    /**
     * Polls once for the {@code checkId} workflow's runs at {@code headSha}.
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
