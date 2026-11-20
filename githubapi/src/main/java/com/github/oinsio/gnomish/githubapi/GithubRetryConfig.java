package com.github.oinsio.gnomish.githubapi;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.RetryConfig;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Resilience4j {@link RetryConfig} for the GitHub HTTP core
 * (NFR-R2 of add-tracker-port, design D3, D13; NFR-R1 of
 * add-external-check-github-actions; design D19 of migrate-verify-to-executor-contract): retries
 * infrastructure failures only — network errors, 5xx responses and a rate limit whose pause the
 * retry can wait out — never a business-outcome 4xx (a 404/422 is a quality/domain result, not
 * something a retry can fix).
 *
 * <p>A rate limit is judged, with its pause, by {@link GithubRateLimit}, its one owner. GitHub asks
 * a client not to repeat a request before the pause and warns that an integration that keeps going
 * may be banned, so a rate-limited response is retried only when its pause is at most {@link
 * #MAX_INTERVAL}, and then no sooner than the pause; a longer pause is returned to the caller at
 * once, which acts on it (the {@code scm-status} wait hint, the tracker's classification).
 *
 * <p>Policy: up to 3 retries (4 attempts total), exponential backoff starting
 * at 500ms and doubling, capped at 8s — and for a rate limit, the larger of that backoff and the
 * pause. This class only builds the {@link RetryConfig}; {@link GithubHttpClient} owns the {@link
 * io.github.resilience4j.retry.Retry} instance and its execution.
 *
 * <p>Implements NFR-R2 of add-tracker-port, NFR-R1 of
 * add-external-check-github-actions; NFR-R3 of fix-operator-blockers; NFR-R2 of
 * migrate-verify-to-executor-contract.
 */
final class GithubRetryConfig {

    private static final int MAX_ATTEMPTS = 4;
    private static final Duration INITIAL_INTERVAL = Duration.ofMillis(500);

    /** The retry's ceiling: the longest backoff, and the longest rate-limit pause it waits out. */
    static final Duration MAX_INTERVAL = Duration.ofSeconds(8);

    private static final double MULTIPLIER = 2.0;
    private static final IntervalFunction BACKOFF =
            IntervalFunction.ofExponentialBackoff(INITIAL_INTERVAL, MULTIPLIER, MAX_INTERVAL);

    private GithubRetryConfig() {}

    /**
     * Builds the retry policy: retries the transport-level {@link
     * GithubHttpUncheckedIOException} (connect failures, timeouts, I/O
     * errors — see {@link GithubHttpClient}) and the responses {@link #retried} names, waiting
     * {@link #interval} before each retry; any other response or any other exception is not
     * retried — in particular {@link GithubCallInterruptedException}, the stop of the calling
     * thread, which must consume no retry budget (NFR-R3 of fix-operator-blockers).
     */
    static RetryConfig build() {
        return RetryConfig.<HttpResponse<?>>custom()
                .maxAttempts(MAX_ATTEMPTS)
                .intervalBiFunction(GithubRetryConfig::interval)
                .retryOnException(throwable -> throwable instanceof GithubHttpUncheckedIOException)
                .retryOnResult(GithubRetryConfig::retried)
                .build();
    }

    /**
     * Whether {@code response} is retried: a {@code 5xx}, or a rate limit whose pause is at most
     * {@link #MAX_INTERVAL}.
     */
    static boolean retried(HttpResponse<?> response) {
        if (response.statusCode() >= 500) {
            return true;
        }
        return GithubRateLimit.of(response)
                .map(limit -> limit.pause().compareTo(MAX_INTERVAL) <= 0)
                .orElse(false);
    }

    /**
     * The wait before retry number {@code attempt}, in milliseconds: the exponential backoff, or for
     * a rate-limited response the larger of the backoff and the pause GitHub asked for.
     */
    static Long interval(Integer attempt, Either<Throwable, HttpResponse<?>> outcome) {
        long backoff = BACKOFF.apply(attempt);
        if (outcome.isLeft()) {
            return backoff;
        }
        return GithubRateLimit.of(outcome.get())
                .map(limit -> Math.max(backoff, limit.pause().toMillis()))
                .orElse(backoff);
    }
}
