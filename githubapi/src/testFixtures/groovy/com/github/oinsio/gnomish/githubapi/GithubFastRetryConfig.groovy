package com.github.oinsio.gnomish.githubapi

import io.github.resilience4j.core.IntervalBiFunction
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.core.functions.Either
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
 * codes are retried. {@code fastClient(String)} extends the same policy to specs outside this
 * package, which cannot reach the package-private {@code GithubHttpClient} test constructor.
 *
 * <p>Published through this module's {@code testFixtures} source set (task 3.0a of
 * migrate-verify-to-executor-contract): the vendor bundle's tracker and check specs, in another
 * module, take the same seam rather than a copy of it.
 *
 * <p>Both policies fix maxAttempts at 4, matching production. A spec that needs a different
 * attempt budget builds its own {@link RetryConfig} rather than parameterising these.
 */
class GithubFastRetryConfig {

    /**
     * A client on the package-private {@code GithubHttpClient} test seam, wired with {@link
     * #withRateLimiting()} — 4 attempts, 10ms interval, production's result predicate. Lives here
     * because the seam is package-private: specs in other packages (the vendor bundle's
     * {@code adapter.tracker.github} and {@code adapter.scm.actions} specs) can only reach it
     * through a helper that shares {@code GithubHttpClient}'s package.
     *
     * <p>The token is fixed at {@code 'tok'}: a spec that asserts on the token value itself (token
     * hygiene) needs its own client, not this seam.
     */
    static GithubHttpClient fastClient(String baseUrl) {
        new GithubHttpClient(GithubTestEndpoints.loopback(URI.create(baseUrl)), 'tok', withRateLimiting())
    }

    /**
     * Same package-private test seam as {@link #fastClient(String)}, for a spec that needs its
     * own {@link RetryConfig} shape (a different {@code maxAttempts} or predicate) rather than
     * {@link #withRateLimiting()} — the seam being package-private is the only reason a spec
     * outside {@code githubapi} cannot build the client itself.
     */
    static GithubHttpClient fastClient(String baseUrl, RetryConfig retryConfig) {
        new GithubHttpClient(GithubTestEndpoints.loopback(URI.create(baseUrl)), 'tok', retryConfig)
    }

    /** {@link #fastClient(String)} over an endpoint the spec admitted itself — a host off loopback. */
    static GithubHttpClient fastClient(com.github.oinsio.gnomish.domain.egress.AdmittedEndpoint endpoint) {
        new GithubHttpClient(endpoint, 'tok', withRateLimiting())
    }

    /** Retries only server errors (>= 500) — the plain infrastructure-failure policy. */
    static RetryConfig serverErrorsOnly() {
        builder().retryOnResult({ HttpResponse<?> r ->
            r.statusCode() >= 500
        }).build()
    }

    /**
     * Retries what production retries — server errors and a rate limit whose pause is at most the
     * retry's ceiling — through the very predicate {@code GithubRetryConfig.build()} installs, so a
     * spec on this policy exercises the real retry surface rather than a copy of it.
     */
    static RetryConfig withRateLimiting() {
        builder()
                .retryOnResult({ HttpResponse<?> r ->
                    GithubRetryConfig.retried(r)
                })
                .build()
    }

    /**
     * Production's policy whole — its attempts, its predicates and its interval rule — except that
     * the wait is not slept: each interval production would wait is appended to {@code waits} and
     * the retry then waits one millisecond. A spec asserts the wait production chose (design D19 of
     * migrate-verify-to-executor-contract: a rate limit is retried no sooner than its pause) with no
     * real-time wait; Resilience4j's own sleep is a process-wide static, so the interval is the one
     * seam a spec can own.
     */
    static RetryConfig recordingWaits(List<Long> waits) {
        RetryConfig production = GithubRetryConfig.build()
        RetryConfig.<HttpResponse<?>> custom()
                .maxAttempts(production.maxAttempts)
                .retryOnException(production.exceptionPredicate)
                .retryOnResult({ HttpResponse<?> r ->
                    GithubRetryConfig.retried(r)
                })
                .intervalBiFunction({ Integer attempt, Either<Throwable, HttpResponse<?>> outcome ->
                    waits << GithubRetryConfig.interval(attempt, outcome)
                    1L
                } as IntervalBiFunction<HttpResponse<?>>)
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
