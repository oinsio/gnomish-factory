package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.app.port.tracker.TrackerUnavailableException
import com.github.oinsio.gnomish.domain.egress.ConnectionEndpoint
import com.github.oinsio.gnomish.domain.egress.EgressMedium
import com.github.oinsio.gnomish.domain.egress.EgressRefusal
import com.github.oinsio.gnomish.domain.egress.EgressRefusedException
import com.github.oinsio.gnomish.domain.egress.EndpointDeclaration
import com.github.oinsio.gnomish.githubapi.GithubFastRetryConfig
import com.github.oinsio.gnomish.githubapi.GithubHttpClient
import com.github.oinsio.gnomish.githubapi.GithubTestEndpoints
import com.github.oinsio.gnomish.httpegress.EgressAdmission
import com.github.oinsio.gnomish.httpegress.ExchangeLimitException
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import java.net.http.HttpRequest
import spock.lang.Specification

/**
 * NFR-R5, UX4 of migrate-verify-to-executor-contract, design D16 ("A refusal is a configuration
 * fault, not an outage — and only a grant miss is a refusal"): the GitHub core over the guarded
 * exchange, classified the way the tracker's callers read it. A refusal makes no retry and no
 * outage signal; an exchange limit neither; an unresolvable host is the transport outage the
 * tracker's own retry and the terminal-write retry consume.
 */
class GithubRefusalClassificationSpec extends Specification {

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort())
        wireMock.start()
    }

    def cleanup() {
        wireMock.stop()
    }

    private GithubHttpClient client() {
        GithubFastRetryConfig.fastClient(wireMock.baseUrl())
    }

    // NFR-R5, UX4: a refusal reaches the caller as itself, after one request, naming the key
    // Scenario: verification/factory-egress-allowlist / A refusal is neither retried nor an outage
    def "a refused hop makes no further attempt and leaves as a configuration fault, not a tracker outage"() {
        given: 'GitHub answers with a redirect to an address no admitted set names (a literal: no lookup needed)'
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/1/labels'))
                .willReturn(aResponse().withStatus(307).withHeader('Location', 'https://93.184.216.34/exfil')))
        def http = client()

        when: 'a tracker write runs through the transport translation the terminal-write retry reads'
        GithubTransport.run {
            http.send(http.newRequest('/repos/acme/widgets/issues/1/labels')
            .POST(HttpRequest.BodyPublishers.ofString('{"labels":["x"]}')))
        }

        then: 'the refusal itself, unwrapped: no TrackerUnavailableException for a retry to consume'
        def refused = thrown(EgressRefusedException)
        !(refused instanceof TrackerUnavailableException)
        refused.refusal().reason() == EgressRefusal.Reason.NOT_ALLOWLISTED
        refused.refusal().key() == 'tracker.github.api-url'

        and: 'the core sent the one request and no retry'
        wireMock.findAll(anyRequestedFor(anyUrl())).size() == 1
    }

    // NFR-R5: a platform anomaly is an exchange limit, never advice to grant a host, never retried
    // Scenario: verification/factory-egress-allowlist / An exchange limit is not a refusal
    def "a chain of redirects past the hop bound fails naming the limit, with no retry and no grant advice"() {
        given:
        (0..4).each { hop ->
            wireMock.stubFor(get(urlEqualTo("/hop${hop}".toString()))
            .willReturn(aResponse().withStatus(302).withHeader('Location', "/hop${hop + 1}".toString())))
        }
        def http = client()

        when:
        GithubTransport.run { http.send(http.newRequest('/hop0')) }

        then:
        def limit = thrown(ExchangeLimitException)
        limit.limit() == ExchangeLimitException.Limit.REDIRECT_LIMIT
        !(limit instanceof TrackerUnavailableException)
        !limit.message.contains('grant')
        !limit.message.contains('egress')

        and: 'one chain of four requests, not four retried chains'
        wireMock.findAll(anyRequestedFor(anyUrl())).size() == 4
    }

    // NFR-R5, UX4: no name is resolved at load, and a name that does not resolve at connect is an outage
    // Scenario: verification/factory-egress-allowlist / An unresolvable host is an outage, not a refusal
    def "a host that does not resolve is a transport outage the tracker's retry consumes, not a refusal"() {
        given: 'a granted host that no name service answers for, admitted at load without a lookup'
        def endpoint = EgressAdmission.admit(
                new ConnectionEndpoint(URI.create('https://tracker.gnomish.invalid'), EgressMedium.LAW, []),
                new EndpointDeclaration('tracker.github.api-url', 'api.github.com'),
                new OperatorEgress(['tracker.gnomish.invalid']))
        def http = GithubFastRetryConfig.fastClient(endpoint)

        when:
        GithubTransport.run {
            http.send(http.newRequest('/repos/acme/widgets/issues'))
        }

        then: 'the outage the tracker port names, carrying the transport failure'
        def outage = thrown(GithubTransportException)
        outage instanceof TrackerUnavailableException
        !(outage.cause instanceof EgressRefusedException)
    }

    def "the helper admits a loopback URL as the tracker's inline api-url granted by loopback"() {
        expect:
        GithubTestEndpoints.loopback(URI.create(wireMock.baseUrl())).key() == 'tracker.github.api-url'
        GithubTestEndpoints.loopback(URI.create(wireMock.baseUrl())).admitted() == ['loopback']
    }
}
