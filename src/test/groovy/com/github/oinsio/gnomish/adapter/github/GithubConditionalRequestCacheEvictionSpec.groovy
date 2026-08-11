package com.github.oinsio.gnomish.adapter.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching

import com.github.tomakehurst.wiremock.WireMockServer
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import spock.lang.Specification

/**
 * NFR-C1 of add-external-check-github-actions (bound on GithubConditionalRequestCache growth,
 * design D15): the ETag cache
 * evicts its eldest key only once the 500-entry bound is EXCEEDED — filling it to exactly the
 * bound keeps every key conditional, so a working set at the bound loses no rate-limit budget.
 */
class GithubConditionalRequestCacheEvictionSpec extends Specification {

    /** Mirrors the private MAX_ENTRIES bound of GithubConditionalRequestCache. */
    private static final int MAX_ENTRIES = 500

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

    // NFR-C1: eviction fires strictly above the bound — the eldest key survives a fill to
    // exactly MAX_ENTRIES and its next poll is still a budget-free conditional 304.
    def "filling the cache to exactly the bound does not evict the eldest key"() {
        given: 'every resource serves 200 with an ETag; the eldest answers 304 to its own ETag'
        wireMock.stubFor(get(urlMatching('/r/\\d+')).atPriority(5)
                .willReturn(aResponse().withStatus(200).withHeader('ETag', '"pinned"').withBody('fresh')))
        wireMock.stubFor(get(urlEqualTo('/r/0')).withHeader('If-None-Match', equalTo('"pinned"')).atPriority(1)
                .willReturn(aResponse().withStatus(304)))
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
        def cache = new GithubConditionalRequestCache(httpClient)

        and: 'the cache is filled to exactly its bound, key r/0 being the eldest'
        (0..<MAX_ENTRIES).each { i ->
            cache.get(cache.httpClient().newRequest("/r/$i"), "r/$i")
        }

        when: 'the eldest key is polled again'
        def result = cache.get(cache.httpClient().newRequest('/r/0'), 'r/0')

        then: 'its ETag was still cached: the conditional round-trip comes back NotModified'
        result instanceof GithubConditionalRequestCache.NotModified
        result.previousBody() == 'fresh'
    }
}
