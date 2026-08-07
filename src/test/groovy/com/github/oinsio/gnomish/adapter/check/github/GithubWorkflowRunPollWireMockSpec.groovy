package com.github.oinsio.gnomish.adapter.check.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.tomakehurst.wiremock.WireMockServer
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import spock.lang.Specification

/**
 * {@link GithubWorkflowRunPoll} (NFR-R1 of add-external-check-github-actions): every failure that
 * cannot reach a verdict classifies as {@link PollStatus.CannotVerify} with a non-blank reason
 * and details that preserve the underlying cause, instead of propagating an exception or silently
 * reading as {@link PollStatus.Running}. Two shapes: infrastructure failures (network error,
 * persistent 5xx, 429 rate limit) carry a generic reason, while client-side rejections (401, 403,
 * 404, other 4xx — a misconfiguration or bad token) carry a status-specific, actionable reason so
 * the escalation report diagnoses the config error rather than stating a bare status code.
 *
 * Implements NFR-R1, NFR-R3 of add-external-check-github-actions.
 */
class GithubWorkflowRunPollWireMockSpec extends Specification {

    private static final String RUNS_URL = '/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=abc123&per_page=100'

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
                .retryOnException({ true })
                .retryOnResult({ HttpResponse<?> r -> r.statusCode() >= 500 || r.statusCode() == 429 })
                .build()
    }

    private GithubWorkflowRunPoll pollFor(String baseUrl) {
        def httpClient = new GithubHttpClient(baseUrl, 'tok', fastRetryConfig())
        def cache = new GithubConditionalRequestCache(httpClient)
        new GithubWorkflowRunPoll(
                new GithubWorkflowRunQuery(cache, 'acme', 'widgets'),
                new GithubWorkflowJobsFetcher(cache, 'acme', 'widgets'))
    }

    def "a network error classifies as CannotVerify with a non-blank reason and preserved detail"() {
        given:
        def poll = pollFor('http://localhost:1')

        when:
        def status = poll.poll('ci.yml', 'abc123')

        then:
        status instanceof PollStatus.CannotVerify
        def cannotVerify = status as PollStatus.CannotVerify
        !cannotVerify.reason().isBlank()
        !cannotVerify.details().isBlank()
    }

    def "a persistent 5xx that exhausts the retry budget classifies as CannotVerify"() {
        given:
        wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(503).withBody('service unavailable')))
        def poll = pollFor(wireMock.baseUrl())

        when:
        def status = poll.poll('ci.yml', 'abc123')

        then:
        status instanceof PollStatus.CannotVerify
        def cannotVerify = status as PollStatus.CannotVerify
        !cannotVerify.reason().isBlank()
        cannotVerify.details().contains('GithubWorkflowRunInfrastructureException')
        cannotVerify.details().contains('503')
    }

    def "a persistent 429 rate limit classifies as CannotVerify, not a silent Running"() {
        given:
        wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(429).withBody('{"message":"rate limit exceeded"}')))
        def poll = pollFor(wireMock.baseUrl())

        when:
        def status = poll.poll('ci.yml', 'abc123')

        then:
        status instanceof PollStatus.CannotVerify
        !(status as PollStatus.CannotVerify).reason().isBlank()
    }

    def "a client-side rejection classifies as CannotVerify with a status-specific, actionable reason"() {
        given:
        wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(status).withBody('{"message":"nope"}')))
        def poll = pollFor(wireMock.baseUrl())

        when:
        def status0 = poll.poll('ci.yml', 'abc123')

        then: 'the reason names the check, the status, and the diagnosis — not a bare code'
        status0 instanceof PollStatus.CannotVerify
        def cannotVerify = status0 as PollStatus.CannotVerify
        cannotVerify.reason().contains("'ci.yml'")
        cannotVerify.reason().contains(status as String)
        cannotVerify.reason().contains(diagnosisFragment)
        cannotVerify.details().contains('GithubWorkflowRunUnverifiableException')

        where:
        status | diagnosisFragment
        401    | 'token is invalid or expired'
        403    | 'lacks permission'
        404    | 'no workflow by that file name'
        422    | 'rejected the runs query'
    }

    def "a run concluding success still maps through to Pass once the platform answers"() {
        given:
        wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(200).withBody('''
                {"workflow_runs":[
                    {"id":1,"head_sha":"abc123","path":"ci.yml","run_attempt":1,"status":"completed","conclusion":"success"}
                ]}
                ''')))
        def poll = pollFor(wireMock.baseUrl())

        expect:
        poll.poll('ci.yml', 'abc123') instanceof PollStatus.Pass
    }
}
