package com.github.oinsio.gnomish.adapter.check.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.absent
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification

/**
 * {@link GithubWorkflowJobsFetcher} edge cases not covered by {@link
 * GithubWorkflowFailureFindingsWireMockSpec}'s main scenario (FR6, NFR-C1 of
 * add-external-check-github-actions): a job that did not succeed but has no failed step of its
 * own, a run with no platform URL, and conditional-cache reuse (a {@code 304}) across repeated
 * fetches of the same run's jobs.
 *
 * <p>Implements FR6, NFR-C1 of add-external-check-github-actions.
 */
class GithubWorkflowJobsFetcherSpec extends Specification {

    private static final String JOBS_URL = '/repos/acme/widgets/actions/runs/1/jobs?per_page=100'

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
    }

    def cleanup() {
        wireMock.stop()
    }

    private GithubWorkflowJobsFetcher fetcherFor(String baseUrl) {
        def cache = new GithubConditionalRequestCache(new GithubHttpClient(baseUrl, 'tok'))
        new GithubWorkflowJobsFetcher(cache, 'acme', 'widgets')
    }

    def "a job that did not succeed with no failed step of its own reports a generic message"() {
        given:
        wireMock.stubFor(get(urlEqualTo(JOBS_URL)).willReturn(aResponse().withStatus(200).withBody('''
                {"jobs":[
                    {"id":10,"name":"build","status":"completed","conclusion":"cancelled","steps":[]}
                ]}
                ''')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/actions/jobs/10/logs'))
                .willReturn(aResponse().withStatus(200).withBody('boom')))
        def fetcher = fetcherFor(wireMock.baseUrl())
        def run = new GithubWorkflowRun(1L, 'abc123', 'ci.yml', 1, 'completed', 'failure', null)

        when:
        def findings = fetcher.failureFindings(run)

        then:
        findings.size() == 1
        findings[0].message() == "Job 'build' did not succeed"
    }

    def "a finding's details omit the run link when the run has no platform URL"() {
        given:
        wireMock.stubFor(get(urlEqualTo(JOBS_URL)).willReturn(aResponse().withStatus(200).withBody('''
                {"jobs":[
                    {"id":10,"name":"build","status":"completed","conclusion":"failure","steps":[]}
                ]}
                ''')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/actions/jobs/10/logs'))
                .willReturn(aResponse().withStatus(200).withBody('boom')))
        def fetcher = fetcherFor(wireMock.baseUrl())
        def run = new GithubWorkflowRun(1L, 'abc123', 'ci.yml', 1, 'completed', 'failure', null)

        when:
        def findings = fetcher.failureFindings(run)

        then:
        findings[0].details() == 'boom'
    }

    def "reuses the conditional-request cache across repeated job fetches for the same run"() {
        given:
        wireMock.stubFor(get(urlEqualTo(JOBS_URL)).withHeader('If-None-Match', absent())
                .willReturn(aResponse().withStatus(200).withHeader('ETag', '"v1"').withBody('''
                        {"jobs":[
                            {"id":10,"name":"build","status":"completed","conclusion":"failure","steps":[]}
                        ]}
                        ''')))
        wireMock.stubFor(get(urlEqualTo(JOBS_URL)).withHeader('If-None-Match', equalTo('"v1"'))
                .willReturn(aResponse().withStatus(304)))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/actions/jobs/10/logs'))
                .willReturn(aResponse().withStatus(200).withBody('boom')))
        def fetcher = fetcherFor(wireMock.baseUrl())
        def run = new GithubWorkflowRun(1L, 'abc123', 'ci.yml', 1, 'completed', 'failure', null)
        fetcher.failureFindings(run)

        when: 'the same run is fetched again and the platform reports it unchanged'
        def secondFindings = fetcher.failureFindings(run)

        then: 'the previously cached jobs body is reused, producing the same finding'
        secondFindings.size() == 1
        secondFindings[0].message() == "Job 'build' did not succeed"
    }

    def "a log exactly at the tail cap is kept whole, with no truncation marker"() {
        given:
        def exactCapLog = 'x' * GithubWorkflowJobsFetcher.LOG_TAIL_CAP_CHARS
        wireMock.stubFor(get(urlEqualTo(JOBS_URL)).willReturn(aResponse().withStatus(200).withBody('''
                {"jobs":[
                    {"id":10,"name":"build","status":"completed","conclusion":"failure","steps":[]}
                ]}
                ''')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/actions/jobs/10/logs'))
                .willReturn(aResponse().withStatus(200).withBody(exactCapLog)))
        def fetcher = fetcherFor(wireMock.baseUrl())
        def run = new GithubWorkflowRun(1L, 'abc123', 'ci.yml', 1, 'completed', 'failure', null)

        when:
        def findings = fetcher.failureFindings(run)

        then:
        findings[0].details() == exactCapLog
        !findings[0].details().contains('truncated')
    }

    def "ANSI sequences in a job log are stripped through the findings funnel"() {
        given: 'FR15 of add-sandbox-core: log content is routed through the funnel at poll time'
        wireMock.stubFor(get(urlEqualTo(JOBS_URL)).willReturn(aResponse().withStatus(200).withBody('''
                {"jobs":[
                    {"id":10,"name":"build","status":"completed","conclusion":"failure","steps":[]}
                ]}
                ''')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/actions/jobs/10/logs'))
                .willReturn(aResponse().withStatus(200).withBody('\u001B[31mFAILED\u001B[0m assertion')))
        def fetcher = fetcherFor(wireMock.baseUrl())
        def run = new GithubWorkflowRun(1L, 'abc123', 'ci.yml', 1, 'completed', 'failure', null)

        when:
        def findings = fetcher.failureFindings(run)

        then:
        findings[0].details() == 'FAILED assertion'
    }
}
