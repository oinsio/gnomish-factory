package com.github.oinsio.gnomish.adapter.check.github;

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache;
import com.github.oinsio.gnomish.app.findings.FindingsSanitizer;
import com.github.oinsio.gnomish.domain.engine.Finding;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds {@link Finding}s for a failed workflow run: lists the run's jobs, narrows to jobs
 * that did not succeed (conclusion != {@code success}, mirroring the fail-closed philosophy
 * of {@link GithubWorkflowRunVerdict} / design D1) and fetches each such job's log, routed
 * through the findings funnel at poll time: ANSI/control sequences stripped and only the
 * capped tail kept ({@link FindingsSanitizer}), so a hostile multi-gigabyte CI log is a
 * bounded finding, not a resource attack.
 *
 * <p>Implements FR6, NFR-C1 of add-external-check-github-actions; FR15, NFR-C1 of
 * add-sandbox-core.
 */
public record GithubWorkflowJobsFetcher(GithubConditionalRequestCache cache, String owner, String repo) {

    private static final String SUCCESS_CONCLUSION = "success";

    /**
     * Max characters kept from the tail of one failed job's log. 4000 chars is roughly
     * 500-800 lines of typical build output — enough to carry a stack trace or assertion
     * failure (design D5: "the tail of a failed job carries the error") while keeping a
     * handful of failed jobs' findings small enough for one executor request's feedback
     * context. Applied through the funnel's {@link FindingsSanitizer#capTail} (FR15,
     * NFR-C1 of add-sandbox-core).
     */
    static final int LOG_TAIL_CAP_CHARS = 4000;

    /**
     * Returns one {@link Finding} per job that did not succeed, naming its failed steps and
     * carrying the (possibly truncated) tail of its log plus the run's URL when known; never
     * null, possibly empty if the run reports no failed jobs.
     *
     * @param run the failed run to report on
     */
    public List<Finding> failureFindings(GithubWorkflowRun run) {
        List<Finding> findings = new ArrayList<>();
        for (GithubWorkflowJob job : fetchJobs(run.id())) {
            if (SUCCESS_CONCLUSION.equals(job.conclusion())) {
                continue;
            }
            findings.add(findingFor(job, run));
        }
        return List.copyOf(findings);
    }

    // per_page=100 (the platform's page-size ceiling) rather than the default 30: a run with
    // more than 30 jobs must not lose its failed jobs past the first page.
    private List<GithubWorkflowJob> fetchJobs(long runId) {
        String path = "/repos/%s/%s/actions/runs/%d/jobs?per_page=100".formatted(owner, repo, runId);
        String cacheKey = "check-jobs:" + owner + "/" + repo + ":" + runId;
        var result = cache.get(cache.httpClient().newRequest(path), cacheKey);
        String body =
                switch (result) {
                    case GithubConditionalRequestCache.Fresh fresh -> GithubFreshBody.require(fresh);
                    case GithubConditionalRequestCache.NotModified notModified -> notModified.previousBody();
                };
        return GithubWorkflowJobsParser.parseJobs(body);
    }

    private Finding findingFor(GithubWorkflowJob job, GithubWorkflowRun run) {
        String failedSteps = job.steps().stream()
                .filter(step -> !SUCCESS_CONCLUSION.equals(step.conclusion()))
                .map(GithubWorkflowStep::name)
                .collect(Collectors.joining(", "));
        String message = failedSteps.isBlank()
                ? "Job '%s' did not succeed".formatted(job.name())
                : "Job '%s' failed at step(s): %s".formatted(job.name(), failedSteps);
        String logTail = FindingsSanitizer.capTail(FindingsSanitizer.strip(fetchLog(job.id())), LOG_TAIL_CAP_CHARS);
        String details = run.htmlUrl() == null ? logTail : "run: " + run.htmlUrl() + "\n" + logTail;
        return new Finding(message, job.name(), details);
    }

    private String fetchLog(long jobId) {
        String path = "/repos/%s/%s/actions/jobs/%d/logs".formatted(owner, repo, jobId);
        String cacheKey = "check-log:" + owner + "/" + repo + ":" + jobId;
        var result = cache.get(cache.httpClient().newRequest(path), cacheKey);
        return switch (result) {
            case GithubConditionalRequestCache.Fresh fresh -> GithubFreshBody.require(fresh);
            case GithubConditionalRequestCache.NotModified notModified -> notModified.previousBody();
        };
    }
}
