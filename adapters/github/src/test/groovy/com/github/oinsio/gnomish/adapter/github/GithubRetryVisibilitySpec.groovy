package com.github.oinsio.gnomish.adapter.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification

/**
 * FR5 of harden-logging-observability, "Retry storm is visible": a GitHub call that backs off
 * against 429s and 5xx must not do it silently. Each retry names its attempt number, the wait it
 * is about to spend and the failure that caused it; a budget that runs out names the failure it
 * gave up on.
 *
 * <p>The lines are DEBUG on purpose: the layer that finally gives up writes the operator-facing
 * WARN (one failure, one log — `.claude/rules/logging.md`), so this plane is diagnosis only.
 *
 * <p>Implements FR5 of harden-logging-observability.
 */
class GithubRetryVisibilitySpec extends Specification {

    private static final String PATH = '/repos/acme/widgets'

    WireMockServer wireMock
    LogCaptureSupport logs

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
        logs = LogCaptureSupport.attach(GithubHttpClient, Level.DEBUG)
    }

    def cleanup() {
        logs.detach()
        wireMock.stop()
    }

    def "FR5: every retry of a rate-limited call names its attempt and its wait"() {
        given:
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(429).withBody('{}')))
        def client = new GithubHttpClient(wireMock.baseUrl(), 'tok', GithubFastRetryConfig.withRateLimiting())

        when:
        client.send(client.newRequest(PATH))

        then: 'three retries under a four-attempt budget, each numbered and carrying its backoff'
        def retries = logs.list.findAll {
            it.formattedMessage.contains('retry')
        }
        retries.size() == 3
        retries[0].formattedMessage.contains('retry 1 of 3')
        retries[2].formattedMessage.contains('retry 3 of 3')
        retries.every { it.formattedMessage.contains('waiting PT0.01S') }
        retries.every { it.level == Level.DEBUG }
    }

    def "FR5: a 5xx that never clears is retried visibly and the response still reaches the caller"() {
        given:
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503).withBody('{}')))
        def client = new GithubHttpClient(wireMock.baseUrl(), 'tok', GithubFastRetryConfig.withRateLimiting())

        when:
        def response = client.send(client.newRequest(PATH))

        then: 'the backoff is on the record, and the exhausted budget hands the 503 back unchanged'
        logs.list.findAll { it.formattedMessage.contains('retry') }.size() == 3
        response.statusCode() == 503
    }

    def "FR5: a transport failure the budget cannot outlast names what it gave up on"() {
        given: 'a server that is not there at all — every attempt is a connect failure'
        def deadUrl = wireMock.baseUrl()
        wireMock.stop()
        def client = new GithubHttpClient(deadUrl, 'tok', GithubFastRetryConfig.withRateLimiting())

        when:
        client.send(client.newRequest(PATH))

        then:
        thrown(GithubHttpException)

        and: 'the retries and the exhaustion are both on the record, with the cause attached'
        logs.list.findAll { it.formattedMessage.contains('retry') }.size() == 3
        def exhaustion = logs.list.find {
            it.formattedMessage.contains('gave up')
        }
        exhaustion.level == Level.DEBUG
        exhaustion.formattedMessage.contains('after 4 attempt(s)')
        exhaustion.throwableProxy.className == GithubHttpUncheckedIOException.name

        cleanup: 'the outer cleanup stops an already-stopped server, which WireMock tolerates'
        wireMock.start()
    }

    def "FR5: a healthy call logs no retry chatter at all"() {
        given:
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody('{}')))
        def client = new GithubHttpClient(wireMock.baseUrl(), 'tok', GithubFastRetryConfig.withRateLimiting())

        when:
        client.send(client.newRequest(PATH))

        then:
        logs.list.isEmpty()
    }
}
