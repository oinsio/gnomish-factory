package com.github.oinsio.gnomish.adapter.check.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.adapter.git.AttemptCommitRef
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.adapter.workspace.AttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.engine.port.contract.ExternalCheckClientContract
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.tomakehurst.wiremock.WireMockServer
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import java.time.Duration

/**
 * {@link GithubCheckExternalClient} against the abstract {@link ExternalCheckClientContract}: all
 * four {@link PollStatus} variants are reachable through a WireMock-scripted platform, since the
 * adapter's mapping (task 3.2/3.4) already covers Pass/Fail/Running/CannotVerify.
 *
 * <p>Implements NFR-R2 of add-external-check-github-actions.
 */
class GithubCheckExternalClientContractSpec extends ExternalCheckClientContract {

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

    private static VerifyCheck.External sampleCheck() {
        new VerifyCheck.External('ci.yml', Duration.ofSeconds(30), Duration.ofMinutes(5), VerifyCheck.TimeoutClass.QUALITY)
    }

    private static AttemptCommitWorkspace sampleWorkspace() {
        def ref = new AttemptCommitRef()
        ref.record('abc123')
        new AttemptCommitWorkspace(ref)
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

    private GithubCheckExternalClient clientFor(String baseUrl) {
        new GithubCheckExternalClient(new GithubHttpClient(baseUrl, 'tok', fastRetryConfig()), 'acme', 'widgets')
    }

    @Override
    protected Optional<PollStatus> arrange(ExternalCheckClientContract.PollVariant variant) {
        switch (variant) {
            case ExternalCheckClientContract.PollVariant.PASS:
                wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(200).withBody('''
                        {"workflow_runs":[
                            {"id":1,"head_sha":"abc123","path":"ci.yml","run_attempt":1,"status":"completed","conclusion":"success"}
                        ]}
                        ''')))
                break
            case ExternalCheckClientContract.PollVariant.RUNNING:
                wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(200).withBody('{"workflow_runs":[]}')))
                break
            case ExternalCheckClientContract.PollVariant.FAIL_WITH_FINDINGS:
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
            case ExternalCheckClientContract.PollVariant.CANNOT_VERIFY:
                wireMock.stubFor(get(urlEqualTo(RUNS_URL))
                .willReturn(aResponse().withStatus(503).withBody('service unavailable')))
                break
        }
        def client = clientFor(wireMock.baseUrl())
        Optional.of(client.poll(sampleCheck(), sampleWorkspace()))
    }

    def "refuses a workspace that is not an AttemptCommitWorkspace, naming its class"() {
        given:
        def client = clientFor(wireMock.baseUrl())
        def foreignWorkspace = new Workspace() {}

        when:
        client.poll(sampleCheck(), foreignWorkspace)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(foreignWorkspace.class.name)
    }

    def "refuses a null workspace, naming it explicitly rather than crashing on a NullPointerException"() {
        given:
        def client = clientFor(wireMock.baseUrl())

        when:
        client.poll(sampleCheck(), null)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('null')
    }
}
