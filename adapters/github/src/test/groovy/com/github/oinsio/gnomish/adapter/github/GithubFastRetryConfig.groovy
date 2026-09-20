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
 * codes are retried. {@code fastClient(String)} extends the same policy to specs outside this
 * package, which cannot reach the package-private {@code GithubHttpClient} test constructor.
 *
 * <p>Both policies fix maxAttempts at 4, matching production. A spec that needs a different
 * attempt budget builds its own {@link RetryConfig} rather than parameterising these.
 */
class GithubFastRetryConfig {

    /**
     * A client on the package-private {@code GithubHttpClient} test seam, wired with {@link
     * #withRateLimiting()} — 4 attempts, 10ms interval, production's result predicate. Lives here
     * because the seam is package-private: specs in sibling packages (the {@code
     * adapter.check.github} workflow specs) can only reach it through a helper that shares
     * {@code GithubHttpClient}'s package.
     *
     * <p>The token is fixed at {@code 'tok'}: a spec that asserts on the token value itself (token
     * hygiene) needs its own client, not this seam.
     */
    static GithubHttpClient fastClient(String baseUrl) {
        new GithubHttpClient(baseUrl, 'tok', withRateLimiting())
    }

    /** Retries only server errors (>= 500) — the plain infrastructure-failure policy. */
    static RetryConfig serverErrorsOnly() {
        builder().retryOnResult({ HttpResponse<?> r ->
            r.statusCode() >= 500
        }).build()
    }

    /**
     * Retries server errors, 429s and a 403 carrying GitHub's rate-limit signal — the same result
     * predicate {@code GithubRetryConfig.build()} installs in production, so a spec on this policy
     * exercises the real retry surface rather than a subset of it.
     */
    static RetryConfig withRateLimiting() {
        builder()
                .retryOnResult({ HttpResponse<?> r ->
                    r.statusCode() >= 500 || r.statusCode() == 429 || GithubRateLimit.isRateLimited(r)
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
