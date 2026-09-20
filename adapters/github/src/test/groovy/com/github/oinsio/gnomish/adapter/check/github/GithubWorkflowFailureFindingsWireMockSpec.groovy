package com.github.oinsio.gnomish.adapter.check.github

import static com.github.oinsio.gnomish.adapter.check.github.GithubWorkflowPollFixture.pollFor
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification

/**
 * {@link GithubWorkflowJobsFetcher} wired through {@link GithubWorkflowRunPoll} on a Fail verdict
 * (task 4.1/4.2, "The gnome sees why CI failed" scenario of github-external-check spec): a
 * failing run's findings name the failed jobs and their failed steps, carry each failed job's log
 * tail (truncated and noted when over the cap), skip jobs that succeeded, and carry the run URL.
 *
 * <p>Implements FR6, NFR-C1, NFR-O1, UX1 of add-external-check-github-actions.
 */
class GithubWorkflowFailureFindingsWireMockSpec extends Specification {

    private static final String RUNS_URL = '/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=abc123&per_page=100'
    private static final String JOBS_URL = '/repos/acme/widgets/actions/runs/1/jobs?per_page=100'
    private static final String RUN_HTML_URL = 'https://github.example/acme/widgets/actions/runs/1'
    private static final int LOG_TAIL_CAP = GithubWorkflowJobsFetcher.LOG_TAIL_CAP_CHARS
    private static final String LONG_LOG = 'x' * (LOG_TAIL_CAP + 1000)
    private static final String TRUNCATION_NOTE =
    "[truncated, showing last ${LOG_TAIL_CAP} of ${LONG_LOG.length()} chars]"

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
    }

    def cleanup() {
        wireMock.stop()
    }

    def "a failing run's findings name failed jobs/steps, carry capped log tails and the run URL"() {
        given:
        wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(200).withBody("""
                {"workflow_runs":[
                    {"id":1,"head_sha":"abc123","path":"ci.yml","run_attempt":1,"status":"completed",
                     "conclusion":"failure","html_url":"${RUN_HTML_URL}"}
                ]}
                """)))
        wireMock.stubFor(get(urlEqualTo(JOBS_URL)).willReturn(aResponse().withStatus(200).withBody('''
                {"jobs":[
                    {"id":10,"name":"build","status":"completed","conclusion":"failure",
                     "steps":[{"name":"Compile","status":"completed","conclusion":"failure"},
                              {"name":"Test","status":"completed","conclusion":"success"}]},
                    {"id":11,"name":"lint","status":"completed","conclusion":"failure",
                     "steps":[{"name":"Run lint","status":"completed","conclusion":"failure"}]},
                    {"id":12,"name":"docs","status":"completed","conclusion":"success","steps":[]}
                ]}
                ''')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/actions/jobs/10/logs'))
                .willReturn(aResponse().withStatus(200).withBody(LONG_LOG)))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/actions/jobs/11/logs'))
                .willReturn(aResponse().withStatus(200).withBody('boom: assertion failed')))
        def poll = pollFor(wireMock.baseUrl())

        when:
        def status = poll.poll('ci.yml', 'abc123')

        then:
        status instanceof PollStatus.Fail
        def findings = (status as PollStatus.Fail).findings()
        findings.size() == 2

        def buildFinding = findings.find { it.location() == 'build' }
        buildFinding.message().contains('build')
        buildFinding.message().contains('Compile')
        !buildFinding.message().contains('Test')
        buildFinding.details().contains(RUN_HTML_URL)
        buildFinding.details().contains(TRUNCATION_NOTE)
        buildFinding.details().endsWith('x' * LOG_TAIL_CAP)

        def lintFinding = findings.find { it.location() == 'lint' }
        lintFinding.message().contains('lint')
        lintFinding.message().contains('Run lint')
        lintFinding.details().contains(RUN_HTML_URL)
        lintFinding.details().contains('boom: assertion failed')
        !lintFinding.details().contains('truncated')
    }
}
