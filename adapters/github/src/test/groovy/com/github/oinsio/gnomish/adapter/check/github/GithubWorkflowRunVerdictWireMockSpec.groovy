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
 * GithubWorkflowRunQuery + GithubWorkflowRunVerdict, end-to-end against a
 * stubbed platform (FR2 of add-external-check-github-actions): a real run
 * query response maps through to the PollStatus a poll would return, for the
 * three verdict cases of FR2 — success run present, failing run present, no
 * run present yet.
 *
 * Implements FR2 of add-external-check-github-actions.
 */
class GithubWorkflowRunVerdictWireMockSpec extends Specification {

    private static final String RUNS_URL =
    '/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=abc123&per_page=100'

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
    }

    def cleanup() {
        wireMock.stop()
    }

    private GithubWorkflowRunQuery newQuery() {
        def retryConfig = RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(10))
                .retryOnException({ true })
                .retryOnResult({ HttpResponse<?> r -> r.statusCode() >= 500 })
                .build()
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', retryConfig)
        def cache = new GithubConditionalRequestCache(httpClient)
        new GithubWorkflowRunQuery(cache, 'acme', 'widgets')
    }

    private PollStatus pollVerdict() {
        def matchingRun = newQuery().latestMatchingRun('ci.yml', 'abc123')
        GithubWorkflowRunVerdict.fromMatchingRun(matchingRun)
    }

    def "a run concluding success maps end-to-end to Pass"() {
        given:
        wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(200).withBody('''
                {"workflow_runs":[
                    {"id":1,"head_sha":"abc123","path":"ci.yml","run_attempt":1,"status":"completed","conclusion":"success"}
                ]}
                ''')))

        expect:
        pollVerdict() instanceof PollStatus.Pass
    }

    def "a run concluding failure maps end-to-end to Fail"() {
        given:
        wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(200).withBody('''
                {"workflow_runs":[
                    {"id":1,"head_sha":"abc123","path":"ci.yml","run_attempt":1,"status":"completed","conclusion":"failure"}
                ]}
                ''')))

        expect:
        pollVerdict() instanceof PollStatus.Fail
    }

    def "no matching run yet maps end-to-end to Running"() {
        given:
        wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(200).withBody('{"workflow_runs":[]}')))

        expect:
        pollVerdict() instanceof PollStatus.Running
    }
}
