package com.github.oinsio.gnomish.adapter.check.github;

/**
 * Thrown by {@link GithubWorkflowRunQuery} when the runs listing response is a client-side
 * rejection the retry policy correctly did not retry — a {@code 401} (invalid/expired token),
 * a non-rate-limited {@code 403} (the token lacks Actions read permission), a {@code 404} (no
 * workflow by that {@code checkId} file name), or any other {@code 4xx}. Unlike {@link
 * GithubWorkflowRunInfrastructureException} — a transient failure the shared plumbing already
 * retried before giving up — this is a misconfiguration or authorization problem that waiting
 * cannot resolve: polling to the check's timeout would only delay an inevitable escalation and
 * burn a stage attempt on a config error. Both exceptions become {@code
 * PollStatus.CannotVerify} so no stage attempt is burned
 * (the domain model already names "unknown check id" as a CannotVerify cause), but this one
 * escalates immediately with an actionable reason instead of being read as an empty runs listing
 * ({@code PollStatus.Running}) that silently polls to the deadline (NFR-R3).
 *
 * <p>Implements NFR-R3 of add-external-check-github-actions.
 */
public final class GithubWorkflowRunUnverifiableException extends RuntimeException {

    private final int statusCode;

    GithubWorkflowRunUnverifiableException(int statusCode) {
        super("GitHub Actions runs query was rejected with status " + statusCode);
        this.statusCode = statusCode;
    }

    /** The HTTP status code the runs listing endpoint returned; always a non-retryable 4xx. */
    public int statusCode() {
        return statusCode;
    }
}
