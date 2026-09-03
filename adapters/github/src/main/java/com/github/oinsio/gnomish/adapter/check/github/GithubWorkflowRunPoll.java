package com.github.oinsio.gnomish.adapter.check.github;

import com.github.oinsio.gnomish.adapter.github.GithubHttpException;
import com.github.oinsio.gnomish.domain.engine.PollStatus;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

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
 * <p>What an operator is told about each outcome — including the level policy for a check that
 * keeps failing to answer — belongs to {@link GithubWorkflowPollLog}, which owns the poll's log
 * plane and the streak state that goes with it (FR4 of harden-logging-observability). This class
 * classifies; that one reports.
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

    private static final String CANNOT_VERIFY_REASON = "GitHub Actions runs query failed";

    private final GithubWorkflowRunQuery query;
    private final GithubWorkflowJobsFetcher jobsFetcher;
    private final GithubWorkflowPollLog outcomeLog;

    /**
     * @param query the runs query this poll classifies the outcome of; never null
     * @param jobsFetcher the failed-job/step findings populator for a Fail verdict; never null
     * @param cannotVerifySuppressor the edge-logging owner for the cannot-verify streak — it
     *     outlives this object, because a poll loop rebuilds the poll per tick and the streak is
     *     what has to survive between them; never null
     */
    public GithubWorkflowRunPoll(
            GithubWorkflowRunQuery query,
            GithubWorkflowJobsFetcher jobsFetcher,
            RepeatSuppressor cannotVerifySuppressor) {
        this.query = query;
        this.jobsFetcher = jobsFetcher;
        this.outcomeLog = new GithubWorkflowPollLog(cannotVerifySuppressor);
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
            status = withFindings(GithubWorkflowRunVerdict.fromMatchingRun(matchingRun), matchingRun.orElse(null));
        } catch (GithubHttpException | GithubWorkflowRunInfrastructureException e) {
            status = new PollStatus.CannotVerify(CANNOT_VERIFY_REASON, render(e));
        } catch (GithubWorkflowRunUnverifiableException e) {
            status = new PollStatus.CannotVerify(misconfigurationReason(checkId, e.statusCode()), render(e));
        }
        outcomeLog.outcome(checkId, headSha, status, matchingRun.orElse(null));
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

    private PollStatus withFindings(PollStatus mapped, @Nullable GithubWorkflowRun matchingRun) {
        if (mapped instanceof PollStatus.Fail && matchingRun != null) {
            return new PollStatus.Fail(jobsFetcher.failureFindings(matchingRun));
        }
        return mapped;
    }

    private static String render(Throwable ex) {
        var writer = new StringWriter();
        ex.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
