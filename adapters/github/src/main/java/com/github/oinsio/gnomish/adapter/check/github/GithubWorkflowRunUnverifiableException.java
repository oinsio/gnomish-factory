package com.github.oinsio.gnomish.adapter.check.github;

/**
 * Thrown by {@link GithubFreshBody} when an Actions response is a client-side
 * rejection the retry policy correctly did not retry — a {@code 401} (invalid/expired token),
 * a non-rate-limited {@code 403} (the token lacks Actions read permission), a {@code 404} (no
 * workflow by that {@code checkId} file name, or no such run, job or log), or any other non-2xx
 * that is not one of the retryable arms. Unlike {@link
 * GithubWorkflowRunInfrastructureException} — a transient failure the shared plumbing already
 * retried before giving up — this is a misconfiguration or authorization problem that waiting
 * cannot resolve: polling to the check's timeout would only delay an inevitable escalation and
 * burn a stage attempt on a config error. Both exceptions become {@code
 * PollStatus.CannotVerify} so no stage attempt is burned
 * (the domain model already names "unknown check id" as a CannotVerify cause), but this one
 * escalates immediately with an actionable reason instead of being read as a runs listing, a jobs
 * listing or a log tail — an empty runs listing reads as {@code PollStatus.Running} and would
 * silently poll to the deadline (NFR-R3).
 *
 * <p>Implements NFR-R3 of add-external-check-github-actions.
 */
public final class GithubWorkflowRunUnverifiableException extends RuntimeException {

    private final int statusCode;

    GithubWorkflowRunUnverifiableException(int statusCode) {
        super("GitHub Actions request was rejected with status " + statusCode);
        this.statusCode = statusCode;
    }

    /** The HTTP status code the Actions endpoint returned; always a non-retryable non-2xx. */
    public int statusCode() {
        return statusCode;
    }
}
