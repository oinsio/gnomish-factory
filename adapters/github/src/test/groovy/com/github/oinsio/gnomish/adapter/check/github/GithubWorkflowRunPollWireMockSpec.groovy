package com.github.oinsio.gnomish.adapter.check.github

import static com.github.oinsio.gnomish.adapter.check.github.GithubWorkflowPollFixture.pollFor
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.untrustedtext.Provenance
import com.github.tomakehurst.wiremock.WireMockServer
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
        wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(httpStatus).withBody('{"message":"nope"}')))
        def poll = pollFor(wireMock.baseUrl())

        when:
        def status = poll.poll('ci.yml', 'abc123')

        then: 'the reason names the check, the status, and the diagnosis — not a bare code'
        status instanceof PollStatus.CannotVerify
        def cannotVerify = status as PollStatus.CannotVerify
        cannotVerify.reason().contains("'ci.yml'")
        cannotVerify.reason().contains(httpStatus as String)
        cannotVerify.reason().contains(diagnosisFragment)
        cannotVerify.details().contains('GithubWorkflowRunUnverifiableException')

        and: 'no byte of it came from GitHub, so it is not filed under the tracker family (design D3)'
        cannotVerify.reason().provenance() == Provenance.MANIFEST

        where:
        httpStatus | diagnosisFragment
        401 | 'token is invalid or expired'
        403 | 'lacks permission'
        404 | 'no workflow by that file name'
        422 | 'rejected the runs query'
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
