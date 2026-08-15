package com.github.oinsio.gnomish.adapter.check.github;

import com.github.oinsio.gnomish.adapter.github.GithubHttpException;
import com.github.oinsio.gnomish.domain.engine.PollStatus;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps one {@link GithubWorkflowRunQuery#latestMatchingRun} call and classifies its outcome
 * into a {@link PollStatus}: on success the matching run is handed to {@link
 * GithubWorkflowRunVerdict} for the Pass/Fail/Running mapping (task 3.2, FR2), and on Fail the
 * {@link GithubWorkflowJobsFetcher} populates the findings with failed jobs/steps and capped log
 * tails (task 4.1, FR6). Three exceptions each become {@link PollStatus.CannotVerify} instead of
 * propagating, so no stage attempt is ever burned on a poll that could not reach a verdict
 * (NFR-R1). Two are infrastructure failures sharing a generic reason: {@link GithubHttpException}
 * — the shared plumbing's signal that a network error exhausted its retry budget — and {@link
 * GithubWorkflowRunInfrastructureException} — a persistent {@code 5xx}/{@code 429} response that
 * survived the same retry budget without becoming a thrown exception (Resilience4j's {@code
 * retryOnResult} returns the last result rather than throwing on exhaustion; see {@code
 * GithubRetryConfig}). The third, {@link GithubWorkflowRunUnverifiableException}, is a client-side
 * rejection (a {@code 401}, permission {@code 403}, {@code 404} or other {@code 4xx}) that no
 * amount of polling can resolve; it too maps to {@link PollStatus.CannotVerify}, but with a
 * status-specific, operator-actionable reason (see {@link #misconfigurationReason}) so a bad
 * token or a mistyped {@code checkId} escalates immediately with a diagnosis rather than after a
 * silent poll to the timeout.
 *
 * <p>Every poll outcome is logged with the run id and, when known, the run's platform URL
 * (task 4.2, NFR-O1). A Pass verdict has no field to carry the run URL onward — {@link
 * PollStatus.Pass} is a bare marker and stays that way (a domain-model change is out of this
 * change's scope) — so for Pass the log line is the only place the run link surfaces; for Fail
 * the URL additionally rides in each finding's {@code details} via {@link
 * GithubWorkflowJobsFetcher}, which is what reaches the tracker report (UX1).
 *
 * <p>Resilience4j retrying of the underlying HTTP call already happens one layer down, inside
 * {@link com.github.oinsio.gnomish.adapter.github.GithubHttpClient#send}: this class adds no
 * second retry layer, it only classifies what the shared plumbing hands back — a successful
 * result, or one of the three failure signals above (NFR-R1).
 *
 * <p>Implements NFR-R1, NFR-R3 of add-external-check-github-actions.
 * <p>Implements FR6, NFR-C1 of add-external-check-github-actions.
 * <p>Implements NFR-O1, UX1 of add-external-check-github-actions.
 */
public final class GithubWorkflowRunPoll {

    private static final Logger log = LoggerFactory.getLogger(GithubWorkflowRunPoll.class);
    private static final String CANNOT_VERIFY_REASON = "GitHub Actions runs query failed";
    private static final String NO_RUN = "none";
    private static final String NO_URL = "unavailable";

    private final GithubWorkflowRunQuery query;
    private final GithubWorkflowJobsFetcher jobsFetcher;

    public GithubWorkflowRunPoll(GithubWorkflowRunQuery query, GithubWorkflowJobsFetcher jobsFetcher) {
        this.query = query;
        this.jobsFetcher = jobsFetcher;
    }

    /**
     * Polls the {@code checkId} workflow's runs for {@code headSha} once and classifies the
     * outcome.
     *
     * <p>Implements NFR-R1, NFR-R3, FR6, NFR-O1 of add-external-check-github-actions.
     *
     * @param checkId the workflow file path identifying the check
     * @param headSha the attempt commit under verification
     * @return {@link PollStatus.Pass}, {@link PollStatus.Fail} (findings populated per FR6) or
     *     {@link PollStatus.Running} per {@link GithubWorkflowRunVerdict} when the runs query
     *     succeeds; {@link PollStatus.CannotVerify} when it could not be answered — either an
     *     infrastructure failure (network error, persistent 5xx, or rate limiting) or a
     *     client-side rejection (401/403/404, a misconfiguration) — never null
     */
    public PollStatus poll(String checkId, String headSha) {
        Optional<GithubWorkflowRun> matchingRun = Optional.empty();
        PollStatus status;
        try {
            matchingRun = query.latestMatchingRun(checkId, headSha);
            status = withFindings(GithubWorkflowRunVerdict.fromMatchingRun(matchingRun), matchingRun);
        } catch (GithubHttpException | GithubWorkflowRunInfrastructureException e) {
            status = new PollStatus.CannotVerify(CANNOT_VERIFY_REASON, render(e));
        } catch (GithubWorkflowRunUnverifiableException e) {
            status = new PollStatus.CannotVerify(misconfigurationReason(checkId, e.statusCode()), render(e));
        }
        logOutcome(checkId, headSha, status, matchingRun);
        return status;
    }

    /**
     * Builds the operator-facing reason for a client-side rejection ({@link
     * GithubWorkflowRunUnverifiableException}), naming the {@code checkId} and the most likely
     * cause so the escalation report is actionable rather than a bare status code (NFR-O1). A
     * {@code 404} in particular is treated as a permanent misconfiguration: a workflow that has
     * simply never run for this commit returns {@code 200} with an empty list, so a {@code 404}
     * means no workflow by that file name is registered — the {@code checkId} must reference a
     * workflow that exists on the repository's default branch.
     */
    private static String misconfigurationReason(String checkId, int statusCode) {
        String cause =
                switch (statusCode) {
                    case 401 -> "the GitHub token is invalid or expired";
                    case 403 -> "the GitHub token lacks permission to read this repository's Actions";
                    case 404 ->
                        "no workflow by that file name exists on the repository's default branch"
                                + " (check the checkId)";
                    default -> "the GitHub API rejected the runs query";
                };
        return "external check '" + checkId + "' cannot be verified (HTTP " + statusCode + "): " + cause;
    }

    private PollStatus withFindings(PollStatus mapped, Optional<GithubWorkflowRun> matchingRun) {
        if (mapped instanceof PollStatus.Fail && matchingRun.isPresent()) {
            return new PollStatus.Fail(jobsFetcher.failureFindings(matchingRun.get()));
        }
        return mapped;
    }

    private void logOutcome(
            String checkId, String headSha, PollStatus status, Optional<GithubWorkflowRun> matchingRun) {
        String runId = matchingRun.map(run -> Long.toString(run.id())).orElse(NO_RUN);
        String runUrl = matchingRun.map(GithubWorkflowRun::htmlUrl).orElse(NO_URL);
        switch (status) {
            case PollStatus.Pass ignored ->
                log.info("GitHub Actions check {}@{} passed: run {} ({})", checkId, headSha, runId, runUrl);
            case PollStatus.Fail fail ->
                log.info(
                        "GitHub Actions check {}@{} failed: run {} ({}), {} finding(s)",
                        checkId,
                        headSha,
                        runId,
                        runUrl,
                        fail.findings().size());
            case PollStatus.Running ignored ->
                log.debug("GitHub Actions check {}@{} still running: run {}", checkId, headSha, runId);
            case PollStatus.CannotVerify cannotVerify ->
                log.warn(
                        "GitHub Actions check {}@{} could not be verified: {}",
                        checkId,
                        headSha,
                        cannotVerify.reason());
        }
    }

    private static String render(Throwable ex) {
        var writer = new StringWriter();
        ex.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
