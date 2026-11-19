package com.github.oinsio.gnomish.githubapi

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.httpegress.ExchangeLimitException
import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Shared
import spock.lang.Specification

/**
 * FR15 of migrate-verify-to-executor-contract, design D16 ("Per consumer, not per leaf"): the GitHub
 * core reads a body whole up to its 16 MiB bound and refuses a longer one as an exchange limit,
 * while a tail read — a job log — keeps the last 16 MiB of a longer body instead; the conditional
 * cache revalidates a tail like any other body.
 */
class GithubTailReadSpec extends Specification {

    /** One MiB past the core's bound, ending in a line a reader of the tail must see. */
    @Shared
    String oversized = ('x' * (GithubHttpClient.MAX_BODY_BYTES + 1024 * 1024)) + '\nthe last line'

    WireMockServer wireMock = new WireMockServer(0)

    def setup() {
        wireMock.start()
        wireMock.stubFor(get(urlEqualTo('/log')).willReturn(aResponse().withStatus(200).withHeader('ETag', '"v1"')
                .withBody(oversized)))
    }

    def cleanup() {
        wireMock.stop()
    }

    private GithubHttpClient client() {
        GithubFastRetryConfig.fastClient(wireMock.baseUrl())
    }

    def "a whole read of a body past the core's bound fails as an exchange limit"() {
        when:
        client().send(client().newRequest('/log'))

        then:
        def e = thrown(ExchangeLimitException)
        e.limit() == ExchangeLimitException.Limit.RESPONSE_SIZE
    }

    def "a tail read keeps the last bytes of a body past the core's bound"() {
        when:
        def response = client().sendTail(client().newRequest('/log'))

        then:
        response.statusCode() == 200
        response.body().length() == GithubHttpClient.MAX_BODY_BYTES
        response.body().endsWith('x\nthe last line')
    }

    def "the cache reads a tail, and revalidates it with the ETag it carried"() {
        given:
        def cache = new GithubConditionalRequestCache(client())

        when:
        def first = cache.getTail(cache.httpClient().newRequest('/log'), 'log')
        wireMock.stubFor(get(urlEqualTo('/log')).willReturn(aResponse().withStatus(304)))
        def second = cache.getTail(cache.httpClient().newRequest('/log'), 'log')

        then:
        (first as GithubConditionalRequestCache.Fresh).body().endsWith('the last line')
        (second as GithubConditionalRequestCache.NotModified).previousBody().endsWith('the last line')
        wireMock.verify(1, getRequestedFor(urlEqualTo('/log')).withHeader('If-None-Match', equalTo('"v1"')))
    }
}
