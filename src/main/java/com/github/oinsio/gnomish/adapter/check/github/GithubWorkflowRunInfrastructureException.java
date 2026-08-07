package com.github.oinsio.gnomish.adapter.check.github;

/**
 * Thrown by {@link GithubWorkflowRunQuery} when the runs listing response itself signals an
 * infrastructure failure — a persistent {@code 5xx} or {@code 429} (rate limit) status code —
 * that survived the shared plumbing's Resilience4j retries (design {@code GithubRetryConfig})
 * without becoming a thrown {@code GithubHttpException}: {@code retryOnResult} without {@code
 * failAfterMaxAttempts} returns the last response rather than throwing once the retry budget is
 * exhausted, so this class is what turns that still-failing response into a classifiable
 * failure instead of letting {@link GithubWorkflowRunParser} attempt to parse an error body as
 * a runs listing.
 *
 * <p>Implements NFR-R1 of add-external-check-github-actions.
 */
public final class GithubWorkflowRunInfrastructureException extends RuntimeException {

    private final int statusCode;

    GithubWorkflowRunInfrastructureException(int statusCode) {
        super("GitHub Actions runs query returned status " + statusCode);
        this.statusCode = statusCode;
    }

    /** The HTTP status code the runs listing endpoint returned; never a 2xx or 304. */
    public int statusCode() {
        return statusCode;
    }
}
