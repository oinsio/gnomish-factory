package com.github.oinsio.gnomish.adapter.tracker.github;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Resilience4j {@link RetryConfig} for the GitHub adapter's HTTP client
 * (NFR-R2 of add-tracker-port, design D3, D13): retries infrastructure
 * failures only — network errors and 5xx responses — never a 4xx business
 * outcome (a 404/422 is a quality/domain result, not something a retry can
 * fix).
 *
 * <p>Policy: up to 3 retries (4 attempts total), exponential backoff starting
 * at 500ms and doubling, capped at 8s. This class only builds the {@link
 * RetryConfig}; {@link GithubHttpClient} owns the {@link
 * io.github.resilience4j.retry.Retry} instance and its execution.
 *
 * <p>Implements NFR-R2 of add-tracker-port.
 */
final class GithubRetryConfig {

    private static final int MAX_ATTEMPTS = 4;
    private static final Duration INITIAL_INTERVAL = Duration.ofMillis(500);
    private static final Duration MAX_INTERVAL = Duration.ofSeconds(8);
    private static final double MULTIPLIER = 2.0;

    private GithubRetryConfig() {}

    /**
     * Builds the retry policy: retries the transport-level {@link
     * GithubHttpUncheckedIOException} (connect failures, timeouts, I/O
     * errors — see {@link GithubHttpClient}) and responses whose status code
     * is {@code >= 500}; a 4xx response or any other exception is not
     * retried.
     */
    static RetryConfig build() {
        return RetryConfig.<HttpResponse<?>>custom()
                .maxAttempts(MAX_ATTEMPTS)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(INITIAL_INTERVAL, MULTIPLIER, MAX_INTERVAL))
                .retryOnException(throwable -> throwable instanceof GithubHttpUncheckedIOException)
                .retryOnResult(response -> response.statusCode() >= 500)
                .build();
    }
}
