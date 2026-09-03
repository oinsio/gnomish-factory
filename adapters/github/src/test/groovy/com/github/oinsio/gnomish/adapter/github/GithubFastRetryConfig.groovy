package com.github.oinsio.gnomish.adapter.github

import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse

/**
 * Shared {@link RetryConfig} builder for {@code GithubHttpClient} specs: the production
 * exception predicate with the backoff collapsed to a few milliseconds, so a spec never
 * actually waits out a real interval.
 *
 * <p>Kept in one place because {@code GithubHttpClientSpec} and
 * {@code GithubRetryVisibilitySpec} both needed the identical maxAttempts/intervalFunction/
 * exception-predicate shape and previously hand-duplicated it, differing only in which result
 * codes are retried.
 */
class GithubFastRetryConfig {

    /** Retries only server errors (>= 500) — the plain infrastructure-failure policy. */
    static RetryConfig serverErrorsOnly() {
        builder().retryOnResult({ HttpResponse<?> r ->
            r.statusCode() >= 500
        }).build()
    }

    /** Retries server errors and 429s — the production rate-limit-aware policy. */
    static RetryConfig withRateLimiting() {
        builder()
                .retryOnResult({ HttpResponse<?> r ->
                    r.statusCode() >= 500 || r.statusCode() == 429
                })
                .build()
    }

    private static RetryConfig.Builder<HttpResponse<?>> builder() {
        RetryConfig.<HttpResponse<?>> custom()
                .maxAttempts(4)
                .intervalFunction(IntervalFunction.of(10))
                .retryOnException({
                    it instanceof GithubHttpUncheckedIOException
                })
    }
}
