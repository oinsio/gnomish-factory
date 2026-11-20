package com.github.oinsio.gnomish.adapter.scm.actions

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.contract.CheckPollContract
import com.github.oinsio.gnomish.githubapi.GithubHttpClient
import com.github.oinsio.gnomish.githubapi.GithubTestEndpoints
import com.github.tomakehurst.wiremock.WireMockServer
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse

/**
 * {@link GithubWorkflowRuns#runsOf}, the judgement the {@code github-actions} built-in renders,
 * against the abstract {@link CheckPollContract}: all four {@link PollStatus} variants are reachable
 * through a WireMock-scripted platform, since the mapping (task 3.2/3.4 of
 * add-external-check-github-actions) covers Pass/Fail/Running/CannotVerify.
 *
 * <p>Implements NFR-R2 of add-external-check-github-actions.
 */
class GithubWorkflowRunsContractSpec extends CheckPollContract {

    private static final String RUNS_URL = '/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=abc123&per_page=100'
    private static final String JOBS_URL = '/repos/acme/widgets/actions/runs/1/jobs?per_page=100'

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
                .retryOnResult({ HttpResponse<?> r ->
                    r.statusCode() >= 500 || r.statusCode() == 429
                })
                .build()
    }

    private static GithubWorkflowRuns clientFor(String baseUrl) {
        new GithubWorkflowRuns(new GithubHttpClient(GithubTestEndpoints.loopback(URI.create(baseUrl)), 'tok', fastRetryConfig()), 'acme', 'widgets', new VirtualClock())
    }

    @Override
    protected Optional<PollStatus> arrange(PollVariant variant) {
        switch (variant) {
            case PollVariant.PASS:
                wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(200).withBody('''
                        {"workflow_runs":[
                            {"id":1,"head_sha":"abc123","path":"ci.yml","run_attempt":1,"status":"completed","conclusion":"success"}
                        ]}
                        ''')))
                break
            case PollVariant.RUNNING:
                wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(200).withBody('{"workflow_runs":[]}')))
                break
            case PollVariant.FAIL_WITH_FINDINGS:
                wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(200).withBody('''
                        {"workflow_runs":[
                            {"id":1,"head_sha":"abc123","path":"ci.yml","run_attempt":1,"status":"completed",
                             "conclusion":"failure","html_url":"https://github.example/acme/widgets/actions/runs/1"}
                        ]}
                        ''')))
                wireMock.stubFor(get(urlEqualTo(JOBS_URL)).willReturn(aResponse().withStatus(200).withBody('''
                        {"jobs":[
                            {"id":10,"name":"build","status":"completed","conclusion":"failure",
                             "steps":[{"name":"Compile","status":"completed","conclusion":"failure"}]}
                        ]}
                        ''')))
                wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/actions/jobs/10/logs'))
                        .willReturn(aResponse().withStatus(200).withBody('boom: assertion failed')))
                break
            case PollVariant.CANNOT_VERIFY:
                wireMock.stubFor(get(urlEqualTo(RUNS_URL))
                .willReturn(aResponse().withStatus(503).withBody('service unavailable')))
                break
        }
        def client = clientFor(wireMock.baseUrl())
        Optional.of(client.runsOf('ci.yml', 'abc123'))
    }
}
