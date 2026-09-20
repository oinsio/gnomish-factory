package com.github.oinsio.gnomish.adapter.check.github;

/**
 * Thrown by {@link GithubFreshBody} when an Actions response itself signals an
 * infrastructure failure — a persistent {@code 5xx}, a {@code 429}, or a {@code 403} carrying a
 * rate-limit signal — that survived the shared plumbing's Resilience4j retries (design {@code
 * GithubRetryConfig}) without becoming a thrown {@code GithubHttpException}: {@code
 * retryOnResult} without {@code failAfterMaxAttempts} returns the last response rather than
 * throwing once the retry budget is exhausted, so this class is what turns that still-failing
 * response into a classifiable failure instead of letting a caller parse an error body as a runs
 * listing, a jobs listing, or a log tail.
 *
 * <p>Implements NFR-R1 of add-external-check-github-actions.
 */
public final class GithubWorkflowRunInfrastructureException extends RuntimeException {

    private final int statusCode;

    GithubWorkflowRunInfrastructureException(int statusCode) {
        super("GitHub Actions request returned status " + statusCode);
        this.statusCode = statusCode;
    }

    /** The HTTP status code the Actions endpoint returned; never a 2xx or 304. */
    public int statusCode() {
        return statusCode;
    }
}
