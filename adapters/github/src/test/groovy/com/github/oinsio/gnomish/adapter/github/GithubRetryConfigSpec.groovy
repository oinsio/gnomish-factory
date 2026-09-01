package com.github.oinsio.gnomish.adapter.github

import java.net.http.HttpHeaders
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

    def "retries a 429 rate-limit response (NFR-R1 of add-external-check-github-actions)"() {
        expect:
        config.resultPredicate.test(responseWithStatus(429))
    }

    def "does not retry a 404, an unrelated 4xx business outcome"() {
        expect:
        !config.resultPredicate.test(responseWithStatus(404))
    }

    def "retries a 403 carrying x-ratelimit-remaining: 0 (primary rate limit, NFR-R1 of add-external-check-github-actions)"() {
        expect:
        config.resultPredicate.test(responseWithHeaders(403, ['x-ratelimit-remaining': ['0']]))
    }

    def "retries a 403 carrying Retry-After (secondary rate limit, NFR-R1 of add-external-check-github-actions)"() {
        expect:
        config.resultPredicate.test(responseWithHeaders(403, ['retry-after': ['30']]))
    }

    def "does not retry a plain permission-denied 403 with neither rate-limit header"() {
        expect:
        !config.resultPredicate.test(responseWithHeaders(403, [:]))
    }

    private HttpResponse<?> responseWithStatus(int status) {
        responseWithHeaders(status, [:])
    }

    private HttpResponse<?> responseWithHeaders(int status, Map<String, List<String>> headerValues) {
        Stub(HttpResponse) {
            statusCode() >> status
            headers() >> HttpHeaders.of(headerValues, (k, v) -> true)
        }
    }
}
