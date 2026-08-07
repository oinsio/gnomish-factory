package com.github.oinsio.gnomish.adapter.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching

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
        result.statusCode() == 200
        result.body() == '{"n":1}'
        result.eTag() == '"v1"'
        wireMock.verify(getRequestedFor(urlEqualTo('/issues/42'))
                .withoutHeader('If-None-Match'))
    }

    def "a non-2xx response is returned Fresh with its status and is not cached, even when it carries an ETag"() {
        given: 'a 404 that (unusually) carries an ETag — it must not seed the conditional cache'
        wireMock.stubFor(get(urlEqualTo('/issues/99'))
                .willReturn(aResponse().withStatus(404).withHeader('ETag', '"gone"').withBody('{"message":"Not Found"}')))
        def cache = new GithubConditionalRequestCache(newClient())

        when:
        def first = cache.get(cache.httpClient().newRequest('/issues/99'), 'issues/99')
        cache.get(cache.httpClient().newRequest('/issues/99'), 'issues/99')

        then: 'the status is surfaced and the next request carries no If-None-Match (nothing was cached)'
        first instanceof GithubConditionalRequestCache.Fresh
        first.statusCode() == 404
        wireMock.verify(2, getRequestedFor(urlEqualTo('/issues/99'))
                .withoutHeader('If-None-Match'))
    }

    def "a 403 carrying x-ratelimit-remaining: 0 is surfaced as rate limited"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/issues/1'))
                .willReturn(aResponse().withStatus(403).withHeader('x-ratelimit-remaining', '0').withBody('{"message":"rate limit"}')))
        def cache = new GithubConditionalRequestCache(newClient())

        when:
        def result = cache.get(cache.httpClient().newRequest('/issues/1'), 'issues/1')

        then:
        result.rateLimited()
    }

    def "a 403 carrying Retry-After (secondary rate limit) is surfaced as rate limited"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/issues/2'))
                .willReturn(aResponse().withStatus(403).withHeader('Retry-After', '30').withBody('{"message":"secondary rate limit"}')))
        def cache = new GithubConditionalRequestCache(newClient())

        when:
        def result = cache.get(cache.httpClient().newRequest('/issues/2'), 'issues/2')

        then:
        result.rateLimited()
    }

    def "a plain permission-denied 403 is not surfaced as rate limited"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/issues/3'))
                .willReturn(aResponse().withStatus(403).withBody('{"message":"Forbidden"}')))
        def cache = new GithubConditionalRequestCache(newClient())

        when:
        def result = cache.get(cache.httpClient().newRequest('/issues/3'), 'issues/3')

        then:
        !result.rateLimited()
    }

    def "a 2xx response without an ETag is not cached"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/issues/7'))
                .willReturn(aResponse().withStatus(200).withBody('{"n":1}')))
        def cache = new GithubConditionalRequestCache(newClient())

        when:
        cache.get(cache.httpClient().newRequest('/issues/7'), 'issues/7')
        cache.get(cache.httpClient().newRequest('/issues/7'), 'issues/7')

        then: 'with no ETag to replay, the second request is unconditional too'
        wireMock.verify(2, getRequestedFor(urlEqualTo('/issues/7'))
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
                .inScenario('eTag-304')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse().withStatus(200).withHeader('ETag', '"v1"').withBody('{"n":1}'))
                .willSetStateTo('cached'))
        wireMock.stubFor(get(urlEqualTo('/issues/42'))
                .inScenario('eTag-304')
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
                .inScenario('eTag-change')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse().withStatus(200).withHeader('ETag', '"v1"').withBody('{"n":1}'))
                .willSetStateTo('changed'))
        wireMock.stubFor(get(urlEqualTo('/issues/42'))
                .inScenario('eTag-change')
                .whenScenarioStateIs('changed')
                .willReturn(aResponse().withStatus(200).withHeader('ETag', '"v2"').withBody('{"n":2}'))
                .willSetStateTo('caching-v2'))
        wireMock.stubFor(get(urlEqualTo('/issues/42'))
                .inScenario('eTag-change')
                .whenScenarioStateIs('caching-v2')
                .willReturn(aResponse().withStatus(304)))
        def cache = new GithubConditionalRequestCache(newClient())
        cache.get(cache.httpClient().newRequest('/issues/42'), 'issues/42')

        when:
        def result = cache.get(cache.httpClient().newRequest('/issues/42'), 'issues/42')

        then:
        result instanceof GithubConditionalRequestCache.Fresh
        result.body() == '{"n":2}'
        result.eTag() == '"v2"'

        when:
        def third = cache.get(cache.httpClient().newRequest('/issues/42'), 'issues/42')

        then:
        wireMock.verify(getRequestedFor(urlEqualTo('/issues/42'))
                .withHeader('If-None-Match', WireMock.equalTo('"v2"')))
        third instanceof GithubConditionalRequestCache.NotModified
        third.previousBody() == '{"n":2}'
    }

    // NFR-C1 of add-tracker-port: the LRU bound evicts strictly ABOVE capacity (size() > MAX_ENTRIES),
    //     not at it — MAX_ENTRIES is the private 500-entry bound; these two tests pin the boundary so a
    //     `>` -> `>=` mutant (which would evict one entry too early, at exactly capacity) is observable.
    private static final int MAX_ENTRIES = 500

    private void seedKeys(GithubConditionalRequestCache cache, int count) {
        wireMock.stubFor(get(urlMatching('/cap/.*'))
                .willReturn(aResponse().withStatus(200).withHeader('ETag', '"e"').withBody('x')))
        (0..<count).each { cache.get(cache.httpClient().newRequest("/cap/${it}"), "/cap/${it}") }
    }

    def "retains every entry exactly at capacity: the eldest key is still cached at MAX_ENTRIES entries"() {
        given: 'exactly MAX_ENTRIES distinct keys are cached, the eldest inserted first'
        def cache = new GithubConditionalRequestCache(newClient())
        seedKeys(cache, MAX_ENTRIES)

        when: 're-requesting the eldest key'
        cache.get(cache.httpClient().newRequest('/cap/0'), '/cap/0')

        then: 'it was never evicted (size() == MAX_ENTRIES does not trip eviction), so its ETag is replayed'
        wireMock.verify(getRequestedFor(urlEqualTo('/cap/0'))
                .withHeader('If-None-Match', WireMock.equalTo('"e"')))
    }

    def "evicts the eldest entry once capacity is exceeded (MAX_ENTRIES + 1)"() {
        given: 'one key beyond capacity is inserted, so the eldest key is evicted'
        def cache = new GithubConditionalRequestCache(newClient())
        seedKeys(cache, MAX_ENTRIES + 1)

        when: 're-requesting the evicted eldest key'
        cache.get(cache.httpClient().newRequest('/cap/0'), '/cap/0')

        then: 'no cached ETag remains for it, so both its requests (seed + re-request) are unconditional'
        wireMock.verify(2, getRequestedFor(urlEqualTo('/cap/0'))
                .withoutHeader('If-None-Match'))
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
