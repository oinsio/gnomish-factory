package com.github.oinsio.gnomish.adapter.scm.actions

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.githubapi.GithubHttpClient
import com.github.oinsio.gnomish.githubapi.GithubTestEndpoints
import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification

/**
 * {@link GithubWorkflowRuns} re-poll/takeover case (NFR-R2 of
 * add-external-check-github-actions, "Polling is stateless and takeover-safe" of the
 * github-external-check spec, scenario "Another instance resumes mid-poll"): the attempt SHA
 * keying (design D2) makes the run match, and therefore the verdict, independent of which client
 * instance asks — a second instance, built fresh with no knowledge of the first, reaches the exact
 * same verdict against the same {@code (owner, repo, attemptCommitSha)}.
 *
 * <p>Implements NFR-R2 of add-external-check-github-actions.
 */
class GithubWorkflowRunsStatelessPollSpec extends Specification {

    private static final String RUNS_URL = '/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=abc123&per_page=100'

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
        wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(200).withBody('''
                {"workflow_runs":[
                    {"id":1,"head_sha":"abc123","path":"ci.yml","run_attempt":1,"status":"completed","conclusion":"success"}
                ]}
                ''')))
    }

    def cleanup() {
        wireMock.stop()
    }

    // Scenario: verification/github-external-check / Another instance resumes mid-poll
    def "a second instance polling after a simulated crash-and-takeover reaches the same verdict with no shared state"() {
        given: 'the first instance polls the attempt commit before a simulated crash'
        def firstInstance = new GithubWorkflowRuns(new GithubHttpClient(GithubTestEndpoints.loopback(URI.create(wireMock.baseUrl())), 'tok'), 'acme', 'widgets', new VirtualClock())
        def firstPoll = firstInstance.runsOf('ci.yml', 'abc123')

        when: 'a second, independently constructed instance resumes the poll after takeover'
        def secondInstance = new GithubWorkflowRuns(new GithubHttpClient(GithubTestEndpoints.loopback(URI.create(wireMock.baseUrl())), 'tok'), 'acme', 'widgets', new VirtualClock())
        def secondPoll = secondInstance.runsOf('ci.yml', 'abc123')

        then: 'both instances observe the same run set and reach the same verdict'
        firstPoll instanceof PollStatus.Pass
        secondPoll instanceof PollStatus.Pass
        firstPoll == secondPoll
    }
}
