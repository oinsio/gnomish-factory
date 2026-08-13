package com.github.oinsio.gnomish.adapter.github

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
 * GithubHttpClient (NFR-R2, NFR-S1 of add-tracker-port, design D5, D13, D15):
 * verifies the Authorization header carries the configured token, and that
 * infrastructure failures (5xx, connection reset) are retried per the
 * Resilience4j policy while a plain 4xx business response is returned as-is,
 * never retried.
 *
 * Implements NFR-R2, NFR-S1 of add-tracker-port.
 */
class GithubHttpClientSpec extends Specification {

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
                .retryOnException({
                    it instanceof GithubHttpUncheckedIOException
                })
                .retryOnResult({ HttpResponse<?> r -> r.statusCode() >= 500 })
                .build()
    }

    def "sends the Authorization header as Bearer <token>"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets'))
                .willReturn(aResponse().withStatus(200).withBody('{}')))
        def client = new GithubHttpClient(wireMock.baseUrl(), 'secret-token-123', fastRetryConfig())

        when:
        def response = client.send(client.newRequest('/repos/acme/widgets'))

        then:
        response.statusCode() == 200
        wireMock.verify(getRequestedFor(urlEqualTo('/repos/acme/widgets'))
                .withHeader('Authorization', WireMock.equalTo('Bearer secret-token-123')))
    }

    def "sends standard Accept and X-GitHub-Api-Version headers"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets'))
                .willReturn(aResponse().withStatus(200).withBody('{}')))
        def client = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())

        when:
        client.send(client.newRequest('/repos/acme/widgets'))

        then:
        wireMock.verify(getRequestedFor(urlEqualTo('/repos/acme/widgets'))
                .withHeader('Accept', WireMock.equalTo('application/vnd.github+json'))
                .withHeader('X-GitHub-Api-Version', WireMock.matching('.+')))
    }

    def "retries a transient 503 and eventually succeeds"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/flaky'))
                .inScenario('flaky-503')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo('second'))
        wireMock.stubFor(get(urlEqualTo('/flaky'))
                .inScenario('flaky-503')
                .whenScenarioStateIs('second')
                .willReturn(aResponse().withStatus(200).withBody('ok')))
        def client = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())

        when:
        def response = client.send(client.newRequest('/flaky'))

        then:
        response.statusCode() == 200
        response.body() == 'ok'
        wireMock.verify(2, getRequestedFor(urlEqualTo('/flaky')))
    }

    def "exhausts retries and surfaces a failure when 5xx persists"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/always-down'))
                .willReturn(aResponse().withStatus(503)))
        def client = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())

        when:
        def response = client.send(client.newRequest('/always-down'))

        then:
        // retryOnResult without a final exception returns the last result once
        // the retry budget is exhausted (no exception was thrown by doSend).
        response.statusCode() == 503
        wireMock.verify(4, getRequestedFor(urlEqualTo('/always-down')))
    }

    def "a 4xx business response is returned as-is, never retried"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/missing'))
                .willReturn(aResponse().withStatus(404).withBody('{"message":"Not Found"}')))
        def client = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())

        when:
        def response = client.send(client.newRequest('/missing'))

        then:
        response.statusCode() == 404
        wireMock.verify(1, getRequestedFor(urlEqualTo('/missing')))
    }

    def "follows a 302 redirect and returns the target's body (e.g. workflow job logs)"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/actions/jobs/1/logs'))
                .willReturn(aResponse().withStatus(302)
                .withHeader('Location', wireMock.baseUrl() + '/blob-storage/log-1')))
        wireMock.stubFor(get(urlEqualTo('/blob-storage/log-1'))
                .willReturn(aResponse().withStatus(200).withBody('log tail contents')))
        def client = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())

        when:
        def response = client.send(client.newRequest('/repos/acme/widgets/actions/jobs/1/logs'))

        then:
        response.statusCode() == 200
        response.body() == 'log tail contents'
    }

    def "a persistent connection failure exhausts retries and throws GithubHttpException"() {
        given:
        def deadUrl = wireMock.baseUrl()
        wireMock.stop()
        def client = new GithubHttpClient(deadUrl, 'tok', fastRetryConfig())

        when:
        client.send(client.newRequest('/unreachable'))

        then:
        thrown(GithubHttpException)
    }
}
