package com.github.oinsio.gnomish.adapter.github;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Resilience4j {@link RetryConfig} for the GitHub adapter's HTTP client
 * (NFR-R2 of add-tracker-port, design D3, D13; NFR-R1 of
 * add-external-check-github-actions): retries infrastructure failures only —
 * network errors, 5xx responses and rate-limit rejections — never a
 * business-outcome 4xx (a 404/422 is a quality/domain result, not something a
 * retry can fix). 429 is included alongside 5xx because it is exactly the
 * "rate limit" infrastructure failure NFR-R1 names for the GHA check adapter;
 * retrying it here keeps the tracker adapter's own 429 handling identical to
 * its existing 5xx handling (previously a 429 fell through as a plain
 * response with no retry) rather than duplicating a second predicate
 * elsewhere. GitHub also reports both its primary and secondary rate limit as
 * a {@code 403} rather than {@code 429} (see {@link GithubRateLimit}); a
 * plain business-outcome {@code 403} (e.g. an unauthorized token) carries
 * neither header and is correctly left unretried.
 *
 * <p>Policy: up to 3 retries (4 attempts total), exponential backoff starting
 * at 500ms and doubling, capped at 8s. This class only builds the {@link
 * RetryConfig}; {@link GithubHttpClient} owns the {@link
 * io.github.resilience4j.retry.Retry} instance and its execution.
 *
 * <p>Implements NFR-R2 of add-tracker-port, NFR-R1 of
 * add-external-check-github-actions.
 */
final class GithubRetryConfig {

    private static final int MAX_ATTEMPTS = 4;
    private static final Duration INITIAL_INTERVAL = Duration.ofMillis(500);
    private static final Duration MAX_INTERVAL = Duration.ofSeconds(8);
    private static final double MULTIPLIER = 2.0;
    private static final int RATE_LIMIT_STATUS = 429;

    private GithubRetryConfig() {}

    /**
     * Builds the retry policy: retries the transport-level {@link
     * GithubHttpUncheckedIOException} (connect failures, timeouts, I/O
     * errors — see {@link GithubHttpClient}) and responses whose status code
     * is {@code >= 500}, is exactly {@code 429}, or is a {@code 403} carrying
     * GitHub's rate-limit signal (see {@link GithubRateLimit}); any other 4xx
     * response or any other exception is not retried.
     */
    static RetryConfig build() {
        return RetryConfig.<HttpResponse<?>>custom()
                .maxAttempts(MAX_ATTEMPTS)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(INITIAL_INTERVAL, MULTIPLIER, MAX_INTERVAL))
                .retryOnException(throwable -> throwable instanceof GithubHttpUncheckedIOException)
                .retryOnResult(response -> response.statusCode() >= 500
                        || response.statusCode() == RATE_LIMIT_STATUS
                        || GithubRateLimit.isRateLimited(response))
                .build();
    }
}
