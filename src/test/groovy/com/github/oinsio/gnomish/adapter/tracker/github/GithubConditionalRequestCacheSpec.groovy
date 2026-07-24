package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import spock.lang.Specification

/**
 * GithubConditionalRequestCache (NFR-P1 of add-tracker-port): verifies the
 * generic conditional-GET building block — first request carries no
 * If-None-Match, a cached ETag is replayed on the next request to the same
 * key, a 304 response is surfaced as "no change" reusing the previously
 * cached body, and a subsequent 200 with a new ETag refreshes the cache.
 *
 * Implements NFR-P1 of add-tracker-port.
 */
class GithubConditionalRequestCacheSpec extends Specification {

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
                .maxAttempts(4)
                .intervalFunction(IntervalFunction.of(10))
                .retryOnException({ it instanceof GithubHttpUncheckedIOException })
                .retryOnResult({ HttpResponse<?> r -> r.statusCode() >= 500 })
                .build()
    }

    private GithubHttpClient newClient() {
        new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
    }

    def "first request for a key carries no If-None-Match and caches the returned ETag"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/issues/42'))
                .willReturn(aResponse().withStatus(200).withHeader('ETag', '"v1"').withBody('{"n":1}')))
        def cache = new GithubConditionalRequestCache(newClient())

        when:
        def result = cache.get(cache.httpClient().newRequest('/issues/42'), 'issues/42')

        then:
        result instanceof GithubConditionalRequestCache.Fresh
        result.body() == '{"n":1}'
        result.etag() == '"v1"'
        wireMock.verify(getRequestedFor(urlEqualTo('/issues/42'))
                .withoutHeader('If-None-Match'))
    }

    def "second identical request sends If-None-Match with the cached ETag"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/issues/42'))
                .willReturn(aResponse().withStatus(200).withHeader('ETag', '"v1"').withBody('{"n":1}')))
        def cache = new GithubConditionalRequestCache(newClient())
        cache.get(cache.httpClient().newRequest('/issues/42'), 'issues/42')

        when:
        cache.get(cache.httpClient().newRequest('/issues/42'), 'issues/42')

        then:
        wireMock.verify(getRequestedFor(urlEqualTo('/issues/42'))
                .withHeader('If-None-Match', WireMock.equalTo('"v1"')))
    }

    def "a 304 response is treated as no change, reusing the previously cached body"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/issues/42'))
                .inScenario('etag-304')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse().withStatus(200).withHeader('ETag', '"v1"').withBody('{"n":1}'))
                .willSetStateTo('cached'))
        wireMock.stubFor(get(urlEqualTo('/issues/42'))
                .inScenario('etag-304')
                .whenScenarioStateIs('cached')
                .willReturn(aResponse().withStatus(304)))
        def cache = new GithubConditionalRequestCache(newClient())
        cache.get(cache.httpClient().newRequest('/issues/42'), 'issues/42')

        when:
        def result = cache.get(cache.httpClient().newRequest('/issues/42'), 'issues/42')

        then:
        result instanceof GithubConditionalRequestCache.NotModified
        result.previousBody() == '{"n":1}'
    }

    def "a changed resource returns a new ETag and body as fresh, updating the cache"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/issues/42'))
                .inScenario('etag-change')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse().withStatus(200).withHeader('ETag', '"v1"').withBody('{"n":1}'))
                .willSetStateTo('changed'))
        wireMock.stubFor(get(urlEqualTo('/issues/42'))
                .inScenario('etag-change')
                .whenScenarioStateIs('changed')
                .willReturn(aResponse().withStatus(200).withHeader('ETag', '"v2"').withBody('{"n":2}'))
                .willSetStateTo('caching-v2'))
        wireMock.stubFor(get(urlEqualTo('/issues/42'))
                .inScenario('etag-change')
                .whenScenarioStateIs('caching-v2')
                .willReturn(aResponse().withStatus(304)))
        def cache = new GithubConditionalRequestCache(newClient())
        cache.get(cache.httpClient().newRequest('/issues/42'), 'issues/42')

        when:
        def result = cache.get(cache.httpClient().newRequest('/issues/42'), 'issues/42')

        then:
        result instanceof GithubConditionalRequestCache.Fresh
        result.body() == '{"n":2}'
        result.etag() == '"v2"'

        when:
        def third = cache.get(cache.httpClient().newRequest('/issues/42'), 'issues/42')

        then:
        wireMock.verify(getRequestedFor(urlEqualTo('/issues/42'))
                .withHeader('If-None-Match', WireMock.equalTo('"v2"')))
        third instanceof GithubConditionalRequestCache.NotModified
        third.previousBody() == '{"n":2}'
    }

    def "independent cache keys carry independent ETags"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/issues/1'))
                .willReturn(aResponse().withStatus(200).withHeader('ETag', '"a"').withBody('one')))
        wireMock.stubFor(get(urlEqualTo('/issues/2'))
                .willReturn(aResponse().withStatus(200).withHeader('ETag', '"b"').withBody('two')))
        def cache = new GithubConditionalRequestCache(newClient())
        cache.get(cache.httpClient().newRequest('/issues/1'), 'issues/1')

        when:
        cache.get(cache.httpClient().newRequest('/issues/2'), 'issues/2')

        then:
        wireMock.verify(getRequestedFor(urlEqualTo('/issues/2'))
                .withoutHeader('If-None-Match'))
    }
}
