package com.github.oinsio.gnomish.githubapi

import io.github.resilience4j.core.functions.Either
import java.net.http.HttpResponse
import spock.lang.Specification

/**
 * {@link GithubRetryConfig} (NFR-R2 of add-tracker-port): direct unit coverage of the retry
 * predicates themselves. {@code GithubHttpClientSpec} exercises retry BEHAVIOR end to end against
 * WireMock, but through {@code GithubFastRetryConfig} (a faster interval function over an
 * equivalent policy) — never {@link GithubRetryConfig#build()} itself — so the exact boundary of
 * {@code retryOnResult} (>= 500) and the type-check of {@code retryOnException} are covered here
 * directly against the real policy object.
 *
 * <p>Implements NFR-R2 of add-tracker-port.
 */
class GithubRetryConfigSpec extends Specification {

    def config = GithubRetryConfig.build()

    def "retries a GithubHttpUncheckedIOException"() {
        expect:
        config.exceptionPredicate.test(new GithubHttpUncheckedIOException(new IOException('boom')))
    }

    def "does not retry an unrelated exception"() {
        expect:
        !config.exceptionPredicate.test(new IllegalStateException('not a transport failure'))
    }

    def "retries a 500 response, the exact lower boundary of server errors"() {
        expect:
        config.resultPredicate.test(responseWithStatus(500))
    }

    def "does not retry a 499 response, just below the server-error boundary"() {
        expect:
        !config.resultPredicate.test(responseWithStatus(499))
    }

    def "does not retry a 404, an unrelated 4xx business outcome"() {
        expect:
        !config.resultPredicate.test(responseWithStatus(404))
    }

    // NFR-R2 of migrate-verify-to-executor-contract (design D19): a rate limit is retried only when
    //     its pause fits under the retry's ceiling of 8 s; a longer one goes back to the caller.
    def "a rate limit is retried only when its pause is at most 8 s: #why"() {
        expect:
        config.resultPredicate.test(responseWithHeaders(status, headers)) == retried

        where:
        why | status | headers || retried
        'a 429 naming 2 s' | 429 | ['retry-after': ['2']] || true
        'a 429 naming exactly 8 s' | 429 | ['retry-after': ['8']] || true
        'a 429 naming 9 s' | 429 | ['retry-after': ['9']] || false
        'a 429 naming nothing: the 60 s default' | 429 | [:] || false
        'a primary-limit 403, reset already passed' | 403 | ['x-ratelimit-remaining': ['0'], 'x-ratelimit-reset': ['100'], 'date': [DATE]] || true
        'a primary-limit 403, no reset: the 60 s default' | 403 | ['x-ratelimit-remaining': ['0']] || false
        'a secondary-limit 403 naming 30 s' | 403 | ['retry-after': ['30']] || false
        'a secondary-limit 403 naming 1 s' | 403 | ['retry-after': ['1']] || true
        'a plain permission-denied 403' | 403 | [:] || false
    }

    def "the wait before retry #attempt of a #why is #millis ms"() {
        expect:
        GithubRetryConfig.interval(attempt, outcome) == millis

        where:
        why | attempt | outcome || millis
        'transport failure' | 1 | Either.left(new GithubHttpUncheckedIOException(new IOException('x'))) || 500
        'transport failure' | 3 | Either.left(new GithubHttpUncheckedIOException(new IOException('x'))) || 2000
        '5xx' | 2 | Either.right(responseWithStatus(503)) || 1000
        'rate limit longer than the backoff' | 1 | Either.right(responseWithHeaders(429, ['retry-after': ['2']])) || 2000
        'rate limit shorter than the backoff' | 3 | Either.right(responseWithHeaders(429, ['retry-after': ['1']])) || 2000
        'rate limit already over' | 1 | Either.right(responseWithHeaders(429, ['retry-after': ['0']])) || 500
    }

    def "the backoff stays capped at the 8 s ceiling"() {
        expect:
        GithubRetryConfig.interval(10, Either.right(responseWithStatus(500))) == GithubRetryConfig.MAX_INTERVAL.toMillis()
        config.maxAttempts == 4
    }

    private static final String DATE = 'Thu, 12 Nov 2026 10:00:00 GMT'

    private static HttpResponse<?> responseWithStatus(int status) {
        responseWithHeaders(status, [:])
    }

    private static HttpResponse<?> responseWithHeaders(int status, Map<String, List<String>> headerValues) {
        RateLimitResponses.of(status, headerValues)
    }
}
