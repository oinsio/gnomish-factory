package com.github.oinsio.gnomish.adapter.check.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import spock.lang.Specification

/**
 * {@link GithubWorkflowRunQuery}'s {@code freshBody} classification of a non-2xx runs response
 * (NFR-R1 of add-external-check-github-actions): an error body has no {@code workflow_runs}
 * array, so it MUST be classified — never handed to the parser where it would read as an empty,
 * silently {@code Running} list. Two classes: a transient infrastructure failure ({@code 5xx},
 * {@code 429}, or a rate-limited {@code 403}) → {@link GithubWorkflowRunInfrastructureException};
 * a client-side rejection that retrying cannot fix ({@code 401}, permission {@code 403}, {@code
 * 404}, other {@code 4xx}) → {@link GithubWorkflowRunUnverifiableException}.
 *
 * <p>Implements NFR-R1, NFR-R3 of add-external-check-github-actions.
 */
class GithubWorkflowRunQueryClassificationSpec extends Specification {

    private static final String RUNS_URL = '/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=abc123&per_page=100'

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
    }

    def cleanup() {
        wireMock.stop()
    }

    private GithubWorkflowRunQuery queryReturning(ResponseDefinitionBuilder response) {
        wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(response))
        // Only 5xx retries here, so a rate-limited 403 reaches freshBody as a fresh response —
        // exactly the Resilience4j retryOnResult exhaustion freshBody must still classify.
        def retryConfig = RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(10))
                .retryOnException({ true })
                .retryOnResult({ HttpResponse<?> r -> r.statusCode() >= 500 })
                .build()
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', retryConfig)
        new GithubWorkflowRunQuery(new GithubConditionalRequestCache(httpClient), 'acme', 'widgets')
    }

    def "a persistent 5xx or 429 classifies as an infrastructure failure, distinct from a client rejection"() {
        given:
        def query = queryReturning(aResponse().withStatus(status).withBody('error'))

        when:
        query.latestMatchingRun('ci.yml', 'abc123')

        then: 'InfrastructureException specifically — not the Unverifiable class the 4xx fall-through would give'
        def ex = thrown(GithubWorkflowRunInfrastructureException)
        ex.statusCode() == status

        where:
        // 500 is the exact lower edge of the 5xx range; 429 must stay classified as infrastructure
        // and not be absorbed by the non-2xx (Unverifiable) fall-through below.
        status << [500, 503, 429]
    }

    def "a persistent 403 rate-limit response classifies as an infrastructure failure, not an empty runs listing"() {
        given:
        def query = queryReturning(aResponse().withStatus(403)
                .withHeader('x-ratelimit-remaining', '0').withBody('{"message":"rate limit"}'))

        when:
        query.latestMatchingRun('ci.yml', 'abc123')

        then:
        def ex = thrown(GithubWorkflowRunInfrastructureException)
        ex.statusCode() == 403
    }

    def "a client-side rejection is classified as unverifiable, not parsed as an empty runs listing"() {
        given:
        def query = queryReturning(aResponse().withStatus(status).withBody(body))

        when:
        query.latestMatchingRun('ci.yml', 'abc123')

        then:
        def ex = thrown(GithubWorkflowRunUnverifiableException)
        ex.statusCode() == status

        where:
        status | body
        401    | '{"message":"Bad credentials"}'
        403    | '{"message":"Resource not accessible by integration"}'
        404    | '{"message":"Not Found"}'
        422    | '{"message":"Validation Failed"}'
    }
}
