package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.tomakehurst.wiremock.WireMockServer
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import java.time.Instant
import spock.lang.Specification

/**
 * GithubFeedQuery (FR8 of add-tracker-port): verifies {@code listReady} calls
 * the List Issues API (not Search), filters out pull requests, enriches each
 * remaining issue with abort facts parsed from its comments' structural abort
 * markers, and reuses the conditional-request cache across polls (NFR-P1).
 *
 * Implements FR8 of add-tracker-port.
 */
class GithubFeedQuerySpec extends Specification {

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
                .retryOnException({ it instanceof GithubHttpUncheckedIOException })
                .retryOnResult({ HttpResponse<?> r -> r.statusCode() >= 500 })
                .build()
    }

    private GithubFeedQuery newFeedQuery(String owner = 'acme', String repo = 'widgets', String readyLabel = 'gnomish:ready') {
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
        def cache = new GithubConditionalRequestCache(httpClient)
        new GithubFeedQuery(cache, owner, repo, readyLabel)
    }

    def "queries List Issues with state=open, ready label, ascending by creation"() {
        given:
        wireMock.stubFor(get(urlEqualTo(
                '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        def feedQuery = newFeedQuery()

        when:
        def result = feedQuery.listReady(10)

        then:
        result.isEmpty()
        wireMock.verify(getRequestedFor(urlEqualTo(
                '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100')))
    }

    def "filters out entries carrying a pull_request field"() {
        given:
        wireMock.stubFor(get(urlEqualTo(
                '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"number":1,"pull_request":{"url":"https://api.github.com/repos/acme/widgets/pulls/1"}},
                          {"number":2},
                          {"number":3}
                        ]
                        ''')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/2/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/3/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        def feedQuery = newFeedQuery()

        when:
        def result = feedQuery.listReady(10)

        then:
        result.size() == 2
        result*.ref()*.id() == [
            'github:localhost/acme/widgets#2',
            'github:localhost/acme/widgets#3'
        ]
    }

    def "enriches a ready task with abort facts from the latest abort structural marker comment"() {
        given:
        wireMock.stubFor(get(urlEqualTo(
                '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[{"number":7}]')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/7/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"id":1,"body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T10:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed"},
                          {"id":2,"body":"<!-- gnomish {\\"kind\\":\\"abort\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T11:00:00Z\\",\\"version\\":1} -->\\n🤖 aborted: network error"},
                          {"id":3,"body":"<!-- gnomish {\\"kind\\":\\"abort\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T12:30:00Z\\",\\"version\\":1} -->\\n🤖 aborted: network error again"}
                        ]
                        ''')))
        def feedQuery = newFeedQuery()

        when:
        def result = feedQuery.listReady(10)

        then:
        result.size() == 1
        result[0].ref().id() == 'github:localhost/acme/widgets#7'
        result[0].abortFacts().count() == 2
        result[0].abortFacts().lastAbortAt() == Instant.parse('2026-07-20T12:30:00Z')
    }

    def "counts only abort markers strictly after the latest PROGRESS marker (FR3, D3 of fix-abort-progress-reset)"() {
        given:
        wireMock.stubFor(get(urlEqualTo(
                '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[{"number":8}]')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/8/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"id":1,"body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T08:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed"},
                          {"id":2,"body":"<!-- gnomish {\\"kind\\":\\"abort\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T08:30:00Z\\",\\"version\\":1} -->\\n🤖 aborted: before progress"},
                          {"id":3,"body":"<!-- gnomish {\\"kind\\":\\"progress\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T09:00:00Z\\",\\"version\\":1} -->\\n🤖 progressed"},
                          {"id":4,"body":"<!-- gnomish {\\"kind\\":\\"abort\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T10:00:00Z\\",\\"version\\":1} -->\\n🤖 aborted: after progress"}
                        ]
                        ''')))
        def feedQuery = newFeedQuery()

        when:
        def result = feedQuery.listReady(10)

        then:
        result.size() == 1
        result[0].abortFacts().count() == 1
        result[0].abortFacts().lastAbortAt() == Instant.parse('2026-07-20T10:00:00Z')
    }

    def "a ready task with no abort markers reports AbortFacts.none()"() {
        given:
        wireMock.stubFor(get(urlEqualTo(
                '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[{"number":9}]')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/9/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        def feedQuery = newFeedQuery()

        when:
        def result = feedQuery.listReady(10)

        then:
        result[0].abortFacts() == AbortFacts.none()
    }

    // FR7, NFR-P1: a REPORT marker (park report) anywhere in the thread sets returned = true,
    //     derived from the SAME comments fetch used for abort facts — no extra API call
    def "reports returned = true when the thread carries a REPORT (park) marker"() {
        given:
        wireMock.stubFor(get(urlEqualTo(
                '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[{"number":11}]')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/11/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"id":1,"body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T10:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed"},
                          {"id":2,"body":"<!-- gnomish {\\"kind\\":\\"report\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T11:00:00Z\\",\\"version\\":1,\\"reason\\":\\"escalation\\"} -->\\n🤖 stuck: needs a human decision"}
                        ]
                        ''')))
        def feedQuery = newFeedQuery()

        when:
        def result = feedQuery.listReady(10)

        then:
        result[0].returned() == true
        wireMock.verify(1, getRequestedFor(urlEqualTo('/repos/acme/widgets/issues/11/comments?per_page=100')))
    }

    // FR7, NFR-P1: a STALE_CLAIM_REMOVED marker (reaper's holder-transition boundary) sets
    //     returned = true, from the same fetch — one comments call per issue, never two
    def "reports returned = true when the thread carries a STALE_CLAIM_REMOVED marker"() {
        given:
        wireMock.stubFor(get(urlEqualTo(
                '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[{"number":12}]')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/12/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"id":1,"body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T10:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed"},
                          {"id":2,"body":"<!-- gnomish {\\"kind\\":\\"stale_claim_removed\\",\\"instance\\":\\"reaper\\",\\"at\\":\\"2026-07-20T11:00:00Z\\",\\"version\\":1} -->\\n🤖 stale claim removed"}
                        ]
                        ''')))
        def feedQuery = newFeedQuery()

        when:
        def result = feedQuery.listReady(10)

        then:
        result[0].returned() == true
        wireMock.verify(1, getRequestedFor(urlEqualTo('/repos/acme/widgets/issues/12/comments?per_page=100')))
    }

    // FR7: a task never claimed or parked carries no REPORT/STALE_CLAIM_REMOVED history —
    //     returned stays false
    def "reports returned = false when the thread carries neither a REPORT nor a STALE_CLAIM_REMOVED marker"() {
        given:
        wireMock.stubFor(get(urlEqualTo(
                '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[{"number":13}]')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/13/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        def feedQuery = newFeedQuery()

        when:
        def result = feedQuery.listReady(10)

        then:
        result[0].returned() == false
    }

    def "listReady rejects a zero limit at the exact boundary, not just negative values"() {
        given:
        def feedQuery = newFeedQuery()

        when:
        feedQuery.listReady(0)

        then:
        thrown(IllegalArgumentException)
    }

    def "respects the limit parameter, stopping after limit entries"() {
        given:
        wireMock.stubFor(get(urlEqualTo(
                '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [{"number":1},{"number":2},{"number":3}]
                        ''')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/1/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        def feedQuery = newFeedQuery()

        when:
        def result = feedQuery.listReady(1)

        then:
        result.size() == 1
        result[0].ref().id() == 'github:localhost/acme/widgets#1'
        wireMock.verify(0, getRequestedFor(urlEqualTo('/repos/acme/widgets/issues/2/comments?per_page=100')))
        wireMock.verify(0, getRequestedFor(urlEqualTo('/repos/acme/widgets/issues/3/comments?per_page=100')))
    }

    def "reuses the conditional-request cache across repeated polls of the feed"() {
        given:
        wireMock.stubFor(get(urlEqualTo(
                '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100'))
                .willReturn(aResponse().withStatus(200).withHeader('ETag', '"v1"').withBody('[]')))
        def feedQuery = newFeedQuery()
        feedQuery.listReady(10)

        when:
        feedQuery.listReady(10)

        then:
        wireMock.verify(getRequestedFor(urlEqualTo(
                '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100'))
                .withHeader('If-None-Match', equalTo('"v1"')))
    }
}
