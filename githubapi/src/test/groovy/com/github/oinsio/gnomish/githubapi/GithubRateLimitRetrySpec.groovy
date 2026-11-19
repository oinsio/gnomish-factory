package com.github.oinsio.gnomish.githubapi

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.stubbing.Scenario
import java.time.Duration
import spock.lang.Specification

/**
 * NFR-R2 of migrate-verify-to-executor-contract (design D19): the GitHub core does not repeat a
 * rate-limited request before its pause, and returns one whose pause exceeds its 8 s ceiling at
 * once. The client runs on production's retry policy through its injected {@code RetryConfig}
 * ({@link GithubFastRetryConfig#recordingWaits}): the predicates and the interval rule are
 * production's, and each wait production chose is recorded instead of slept.
 */
class GithubRateLimitRetrySpec extends Specification {

    private static final String PATH = '/repos/acme/widgets/commits/abc/status'

    WireMockServer wireMock = new WireMockServer(0)

    private final List<Long> waits = []

    def setup() {
        wireMock.start()
    }

    def cleanup() {
        wireMock.stop()
    }

    private GithubHttpClient client() {
        GithubFastRetryConfig.fastClient(wireMock.baseUrl(), GithubFastRetryConfig.recordingWaits(waits))
    }

    // Scenario: plugin/github-plugin / A long rate-limit pause is returned, not retried
    def "a rate limit whose pause is #why is returned after one request, with that pause"() {
        given:
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(answer))

        when:
        def client = client()
        def response = client.send(client.newRequest(PATH))

        then: 'no further attempt and no wait'
        wireMock.verify(1, getRequestedFor(urlEqualTo(PATH)))
        waits == []

        and: 'the caller receives the response, whose pause the one owner reads'
        response.statusCode() == status
        GithubRateLimit.of(response) == Optional.of(new RateLimit(pause))

        where:
        why | answer || status | pause
        'a 60 s retry-after' | aResponse().withStatus(429).withHeader('retry-after', '60') || 429 | Duration.ofSeconds(60)
        'a 9 s retry-after' | aResponse().withStatus(403).withHeader('retry-after', '9') || 403 | Duration.ofSeconds(9)
        'unnamed, the 60 s default' | aResponse().withStatus(403).withHeader('x-ratelimit-remaining', '0') || 403 | GithubRateLimit.DEFAULT_PAUSE
    }

    // Scenario: plugin/github-plugin / A short rate-limit pause is retried after it
    def "a retry-after of 2 s is re-sent no sooner than 2 s later, and the success is returned"() {
        given:
        wireMock.stubFor(get(urlEqualTo(PATH)).inScenario('limit').whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader('retry-after', '2'))
                .willSetStateTo('cleared'))
        wireMock.stubFor(get(urlEqualTo(PATH)).inScenario('limit').whenScenarioStateIs('cleared')
                .willReturn(aResponse().withStatus(200).withBody('{"state":"success"}')))

        when:
        def client = client()
        def response = client.send(client.newRequest(PATH))

        then: 'one retry, after the pause production chose'
        wireMock.verify(2, getRequestedFor(urlEqualTo(PATH)))
        waits == [2000L]

        and:
        response.statusCode() == 200
        response.body() == '{"state":"success"}'
    }
}
