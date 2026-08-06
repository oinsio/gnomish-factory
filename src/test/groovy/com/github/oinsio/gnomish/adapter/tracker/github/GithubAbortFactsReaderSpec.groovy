package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.time.Instant
import spock.lang.Specification

/**
 * GithubAbortFactsReader#read (FR8, FR14 of add-tracker-port; FR3 of
 * fix-abort-progress-reset): {@code read} is the fetch-and-fold entry point
 * that combines {@code fetchMarkers} with {@code foldAbortMarkers} in one
 * call. {@link GithubFeedQuery} currently calls the two steps separately to
 * share a single comments fetch with the {@code returned} fact (NFR-P1 of
 * add-factory-serve), but {@code read} remains the reader's own documented
 * single-call contract and must independently return the correct,
 * non-null {@link AbortFacts} for both the "has abort markers" and the
 * "no markers at all" cases.
 */
class GithubAbortFactsReaderSpec extends Specification {

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
    }

    def cleanup() {
        wireMock.stop()
    }

    private static RetryConfig fastRetryConfig() {
        RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(10))
                // Matches everything rather than naming the adapter's package-private
                // GithubHttpUncheckedIOException (illegal cross-package access from this spec's
                // package, see FeedAutomatonOutageIntegrationSpec) -- harmless here since the only
                // exception this predicate ever actually sees is a real transport failure.
                .retryOnException({ true })
                .retryOnResult({ it.statusCode() >= 500 })
                .build()
    }

    private GithubAbortFactsReader newReader() {
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
        new GithubAbortFactsReader(new GithubConditionalRequestCache(httpClient))
    }

    def "read fetches the issue comments and folds abort markers into a non-null AbortFacts"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/7/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"id":1,"body":"<!-- gnomish {\\"kind\\":\\"abort\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T11:00:00Z\\",\\"version\\":1} -->\\n🤖 aborted: network error"},
                          {"id":2,"body":"<!-- gnomish {\\"kind\\":\\"abort\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T12:30:00Z\\",\\"version\\":1} -->\\n🤖 aborted again"}
                        ]
                        ''')))
        def reader = newReader()

        when:
        def result = reader.read('acme', 'widgets', 7)

        then:
        result != null
        result.count() == 2
        result.lastAbortAt() == Instant.parse('2026-07-20T12:30:00Z')
    }

    def "read returns the non-null AbortFacts#none() sentinel when the thread has no abort markers"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/9/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        def reader = newReader()

        when:
        def result = reader.read('acme', 'widgets', 9)

        then:
        result != null
        result == AbortFacts.none()
    }

    def "re-reading an unchanged comment thread sends If-None-Match and reuses the 304 body (NFR-P1)"() {
        given: 'the comments answer 200+ETag once, then 304 on the conditional re-read'
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/7/comments?per_page=100'))
                .inScenario('comments-poll').whenScenarioStateIs('Started')
                .willReturn(aResponse().withStatus(200).withHeader('ETag', '"com1"').withBody('''
                        [
                          {"id":1,"body":"<!-- gnomish {\\"kind\\":\\"abort\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T11:00:00Z\\",\\"version\\":1} -->\\n🤖 aborted"}
                        ]
                        '''))
                .willSetStateTo('comments-cached'))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/7/comments?per_page=100'))
                .inScenario('comments-poll').whenScenarioStateIs('comments-cached')
                .willReturn(aResponse().withStatus(304)))
        def reader = newReader()

        when: 'the same reader (sharing one cache) polls the same issue twice'
        def first = reader.read('acme', 'widgets', 7)
        def second = reader.read('acme', 'widgets', 7)

        then: 'the 304 re-read reuses the cached comments body, yielding the same facts'
        first == new AbortFacts(1, Instant.parse('2026-07-20T11:00:00Z'))
        second == first

        and: 'the second poll carried If-None-Match with the cached ETag'
        wireMock.verify(getRequestedFor(urlEqualTo('/repos/acme/widgets/issues/7/comments?per_page=100'))
                .withHeader('If-None-Match', WireMock.equalTo('"com1"')))
    }

    def "read propagates a GithubFeedQueryException when the comments fetch fails"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/13/comments?per_page=100'))
                .willReturn(aResponse().withStatus(404)))
        def reader = newReader()

        when:
        reader.read('acme', 'widgets', 13)

        then:
        thrown(GithubFeedQueryException)
    }
}
