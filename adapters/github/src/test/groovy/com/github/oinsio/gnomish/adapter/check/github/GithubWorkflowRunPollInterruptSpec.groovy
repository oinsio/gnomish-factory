package com.github.oinsio.gnomish.adapter.check.github

import static com.github.oinsio.gnomish.adapter.check.github.GithubWorkflowPollFixture.pollFor
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.adapter.github.GithubCallInterruptedException
import com.github.oinsio.gnomish.adapter.github.InterruptMidRequest
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification

/**
 * {@link GithubWorkflowRunPoll} under a stop (FR11 of fix-operator-blockers): a runs query
 * interrupted mid-request is the stop of the polling thread, not a GitHub that could not be
 * reached. The poll therefore returns no {@link PollStatus.CannotVerify} — which would escalate the
 * task as an infrastructure failure blaming a reachable platform — and writes no outcome line; the
 * cancellation propagates unchanged to end the slot. A real network failure keeps its
 * cannot-verify verdict.
 *
 * Implements FR11 of fix-operator-blockers.
 */
class GithubWorkflowRunPollInterruptSpec extends Specification {

    private static final String RUNS_URL = '/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=abc123&per_page=100'

    WireMockServer wireMock
    LogCaptureSupport logs

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
        logs = LogCaptureSupport.attach(GithubWorkflowRunPoll)
    }

    def cleanup() {
        logs.detach()
        wireMock.stop()
    }

    // FR11: the cancellation leaves the poll unwrapped — no verdict, no outcome line.
    def "an interrupted runs query propagates as the cancellation, with no cannot-verify verdict"() {
        given:
        wireMock.stubFor(get(urlEqualTo(RUNS_URL))
                .willReturn(aResponse().withStatus(200).withBody('{"workflow_runs":[]}').withFixedDelay(30_000)))
        def poll = pollFor(wireMock.baseUrl())
        PollStatus status = null

        when:
        def outcome = InterruptMidRequest.run(wireMock, getRequestedFor(urlEqualTo(RUNS_URL))) {
            status = poll.poll('ci.yml', 'abc123')
        }

        then:
        outcome.failure instanceof GithubCallInterruptedException
        status == null
        outcome.interruptSet

        and: 'no outcome line calls the stop an infrastructure failure'
        logs.list.isEmpty()
    }

    // FR11, control row: a query that fails on the network, with no interrupt, is still the
    //     cannot-verify verdict it always was.
    def "a network failure of the runs query still classifies as CannotVerify"() {
        when:
        def status = pollFor('http://localhost:1').poll('ci.yml', 'abc123')

        then:
        status instanceof PollStatus.CannotVerify
        !logs.list.isEmpty()
    }
}
