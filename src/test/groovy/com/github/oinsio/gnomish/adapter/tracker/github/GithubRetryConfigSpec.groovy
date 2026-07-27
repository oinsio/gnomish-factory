package com.github.oinsio.gnomish.adapter.tracker.github

import java.net.http.HttpResponse
import spock.lang.Specification

/**
 * {@link GithubRetryConfig} (NFR-R2 of add-tracker-port): direct unit coverage of the retry
 * predicates themselves. {@code GithubHttpClientSpec} exercises retry BEHAVIOR end to end against
 * WireMock, but through its own {@code fastRetryConfig()} test double (a faster interval function
 * over an equivalent policy) — never {@link GithubRetryConfig#build()} itself — so the exact
 * boundary of {@code retryOnResult} (>= 500) and the type-check of {@code retryOnException} are
 * covered here directly against the real policy object.
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

    private HttpResponse<?> responseWithStatus(int status) {
        Stub(HttpResponse) {
            statusCode() >> status
        }
    }
}
