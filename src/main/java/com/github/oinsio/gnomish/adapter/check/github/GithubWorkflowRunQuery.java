package com.github.oinsio.gnomish.adapter.check.github;

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Queries a workflow's runs filtered by head commit and selects the run
 * matching a check's {@code checkId} workflow file, with the highest {@code
 * run_attempt} winning among matches (design D1, D2). This is the run-query
 * building block a later task (3.2) wires into {@code ExternalCheckClient}'s
 * verdict mapping — this class only returns the raw matching run, or empty
 * when none matches.
 *
 * <p>Queries {@code GET
 * /repos/{owner}/{repo}/actions/workflows/{workflow_id}/runs?head_sha={sha}&per_page=100}
 * (proposal.md Q1 spike; {@code per_page=100} is the platform's page-size ceiling, requested
 * explicitly rather than relying on the default {@code 30} — a head commit that reruns many
 * times must not lose runs past the first page of the match below), where {@code workflow_id}
 * is the {@code checkId}
 * workflow's bare file name (see {@link #workflowFileName} — the full path
 * 404s on Gitea), already scoped server-side by both {@code workflow_id} and
 * {@code head_sha}; this class re-checks the run's workflow file name and
 * {@code headSha} defensively so a platform that returns extra entries never
 * leaks an unrelated run into the match (FR1). Requests go through the
 * shared {@link GithubConditionalRequestCache} so repeated polls of the same
 * attempt commit cost no rate-limit budget once the run set stops changing
 * (NFR-C1). A fresh response whose status code is a persistent {@code
 * 5xx}/{@code 429}, or a {@code 403} carrying GitHub's rate-limit signal
 * (primary or secondary limit, see {@code GithubRateLimit}) — a Resilience4j
 * {@code retryOnResult} exhaustion, which returns the last response rather
 * than throwing (see {@code GithubRetryConfig}) — is classified as {@link
 * GithubWorkflowRunInfrastructureException} before parsing is attempted, so
 * an error body is never mistaken for an empty runs listing (NFR-R1). Any
 * other non-2xx response — a {@code 401}, a permission {@code 403}, a {@code
 * 404}, or any other {@code 4xx} — is a client-side rejection that retrying
 * and polling cannot fix; it is classified as {@link
 * GithubWorkflowRunUnverifiableException} so a misconfigured {@code checkId}
 * or an invalid token escalates immediately instead of silently polling to
 * the timeout (NFR-R1).
 *
 * <p>Implements FR1, FR5 of add-external-check-github-actions.
 */
public final class GithubWorkflowRunQuery {

    private final GithubConditionalRequestCache cache;
    private final String owner;
    private final String repo;

    /**
     * @param cache the shared conditional-request cache; reused across polls
     *     so this query's ETag survives between calls (NFR-C1)
     * @param owner the configured repository owner
     * @param repo the configured repository name
     */
    public GithubWorkflowRunQuery(GithubConditionalRequestCache cache, String owner, String repo) {
        this.cache = cache;
        this.owner = owner;
        this.repo = repo;
    }

    /**
     * Returns the run of the {@code checkId} workflow whose head commit is
     * {@code headSha}, or empty when no such run exists yet. When several
     * runs match, the one with the highest {@code run_attempt} is returned
     * (FR5).
     *
     * @param checkId the workflow file path identifying the check (e.g.
     *     {@code .github/workflows/ci.yml})
     * @param headSha the attempt commit under verification
     * @return the latest matching run, or empty when none match; never null
     */
    public Optional<GithubWorkflowRun> latestMatchingRun(String checkId, String headSha) {
        String workflowFileName = workflowFileName(checkId);
        String encodedWorkflowId = URLEncoder.encode(workflowFileName, StandardCharsets.UTF_8);
        String path = "/repos/%s/%s/actions/workflows/%s/runs?head_sha=%s&per_page=100"
                .formatted(owner, repo, encodedWorkflowId, URLEncoder.encode(headSha, StandardCharsets.UTF_8));
        String cacheKey = "check-runs:" + owner + "/" + repo + ":" + checkId + ":" + headSha;

        var result = cache.get(cache.httpClient().newRequest(path), cacheKey);
        String body =
                switch (result) {
                    case GithubConditionalRequestCache.Fresh fresh -> GithubFreshBody.require(fresh);
                    case GithubConditionalRequestCache.NotModified notModified -> notModified.previousBody();
                };

        List<GithubWorkflowRun> runs = GithubWorkflowRunParser.parseRuns(body);
        return runs.stream()
                .filter(run -> workflowFileName.equals(workflowFileName(run.path())) && headSha.equals(run.headSha()))
                .max(Comparator.comparingInt(GithubWorkflowRun::runAttempt));
    }

    /**
     * Reduces a workflow-file identifier to its bare file name, the form that scopes the runs
     * query ({@code {workflow_id}}) and that both sides of the run match are compared in. The
     * runs endpoint accepts the workflow file <em>name</em>, not its repository path (proposal Q1),
     * and the two platforms report the run's {@code path} differently: GitHub returns the full
     * workflow file path (e.g. {@code .github/workflows/ci.yml}), Gitea returns the file name with
     * a {@code @refs/heads/<branch>} ref suffix (e.g. {@code ci.yml@refs/heads/main}). Dropping any
     * {@code @ref} suffix, then any directory prefix, yields the same file name on both — the live
     * Gitea E2E (task 7.1) surfaced this shape gap the Swagger-only spike missed (design D1, D2).
     */
    private static String workflowFileName(String workflowPath) {
        int refSeparator = workflowPath.indexOf('@');
        String withoutRef = refSeparator < 0 ? workflowPath : workflowPath.substring(0, refSeparator);
        return withoutRef.substring(withoutRef.lastIndexOf('/') + 1);
    }
}
