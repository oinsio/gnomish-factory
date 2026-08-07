package com.github.oinsio.gnomish.adapter.check.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification

/**
 * {@link GithubWorkflowJobsFetcher}'s {@code freshBody} classification of a non-2xx jobs/log
 * response, mirroring {@link GithubWorkflowRunQueryClassificationSpec}: an error body has no
 * {@code jobs} array and is not a log tail, so it MUST be classified — never handed to {@link
 * GithubWorkflowJobsParser} where it would read as an empty job list, and never kept verbatim as
 * a job's log tail.
 *
 * <p>Implements FR6, NFR-R1 of add-external-check-github-actions.
 */
class GithubWorkflowJobsFetcherClassificationSpec extends Specification {

    private static final String JOBS_URL = '/repos/acme/widgets/actions/runs/1/jobs?per_page=100'
    private static final String LOG_URL = '/repos/acme/widgets/actions/jobs/10/logs'

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

    private GithubWorkflowRun failedRun() {
        new GithubWorkflowRun(1L, 'abc123', 'ci.yml', 1, 'completed', 'failure', null)
    }

    def "a non-2xx jobs response is classified, not parsed as an empty job list"() {
        given:
        wireMock.stubFor(get(urlEqualTo(JOBS_URL)).willReturn(aResponse().withStatus(status).withBody('error')))
        def fetcher = fetcherFor(wireMock.baseUrl())

        when:
        fetcher.failureFindings(failedRun())

        then:
        def ex = thrown(expectedException)
        ex.statusCode() == status

        where:
        status | expectedException
        500    | GithubWorkflowRunInfrastructureException
        503    | GithubWorkflowRunInfrastructureException
        429    | GithubWorkflowRunInfrastructureException
        404    | GithubWorkflowRunUnverifiableException
        422    | GithubWorkflowRunUnverifiableException
    }

    def "a non-2xx log response is classified, not kept verbatim as the job's log tail"() {
        given:
        wireMock.stubFor(get(urlEqualTo(JOBS_URL)).willReturn(aResponse().withStatus(200).withBody('''
                {"jobs":[
                    {"id":10,"name":"build","status":"completed","conclusion":"failure","steps":[]}
                ]}
                ''')))
        wireMock.stubFor(get(urlEqualTo(LOG_URL)).willReturn(aResponse().withStatus(status).withBody('error')))
        def fetcher = fetcherFor(wireMock.baseUrl())

        when:
        fetcher.failureFindings(failedRun())

        then:
        def ex = thrown(expectedException)
        ex.statusCode() == status

        where:
        status | expectedException
        500    | GithubWorkflowRunInfrastructureException
        404    | GithubWorkflowRunUnverifiableException
    }
}
