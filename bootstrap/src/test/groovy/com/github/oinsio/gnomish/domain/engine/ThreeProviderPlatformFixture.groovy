package com.github.oinsio.gnomish.domain.engine

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.adapter.check.CheckClientConfiguration
import com.github.oinsio.gnomish.adapter.check.CheckClientDiscovery
import com.github.oinsio.gnomish.adapter.check.CheckProviderSeam
import com.github.oinsio.gnomish.adapter.check.LoopbackTlsFixture
import com.github.oinsio.gnomish.adapter.check.ProviderDispatchingExternalCheckClient
import com.github.oinsio.gnomish.app.ConnectionProfiles
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import java.nio.file.Path
import java.time.Duration
import javax.net.ssl.SSLContext

/**
 * The two third-party platforms {@link ThreeProviderVerifyChainSpec} verifies against, and the
 * production wiring that reaches them: one WireMock serving a GitHub Actions API over plain http and
 * a SonarQube quality gate over TLS, plus the {@code RunAssembler}-shaped check-client assembly over
 * the real discovered registry.
 *
 * <p>Extracted from the spec so the acceptance claim and its staging are read separately — the
 * staging is the uninteresting half, and the file-size rule is the reminder.
 */
class ThreeProviderPlatformFixture {

    static final String REPO = 'acme/widgets'
    static final String WORKFLOW = 'ci.yml'
    static final String QUALITY_GATE = '/api/qualitygates/project_status'
    static final String RUNS_PATH = "/repos/${REPO}/actions/workflows/${WORKFLOW}/runs"
    static final String GREEN_SHA = 'abc123'
    static final String RED_SHA = 'def456'
    static final List<String> LOOPBACK_ALLOWLIST = ['127.0.0.1']

    private static final String SCENARIO = 'quality-gate'
    private static final int RED_RUN = 2
    private static final int RED_JOB = 20

    WireMockServer wireMock
    private SSLContext previousSslContext

    /** Starts both listeners and stubs both platforms; TLS is trusted for the fixture's lifetime. */
    void start(Path tempDir) {
        def keystore = LoopbackTlsFixture.keystore(tempDir)
        previousSslContext = LoopbackTlsFixture.install(keystore)
        wireMock = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .dynamicHttpsPort()
                .keystorePath(keystore.toString())
                .keystoreType('PKCS12')
                .keystorePassword(LoopbackTlsFixture.PASSWORD)
                .keyManagerPassword(LoopbackTlsFixture.PASSWORD))
        wireMock.start()
        stubRun(GREEN_SHA, 1, 'success')
        stubRun(RED_SHA, RED_RUN, 'failure')
        stubFailedJobs()
        stubQualityGate()
    }

    void stop() {
        wireMock?.stop()
        LoopbackTlsFixture.restore(previousSslContext)
    }

    /** Between features: forget the recorded requests and rewind the quality gate to pending. */
    void reset() {
        wireMock.resetRequests()
        wireMock.resetScenarios()
    }

    /**
     * The check client the composition root builds (see {@code RunAssembler.externalCheckClient}):
     * the discovered registry, the operator subsections passed through the real startup gate, and
     * the production dispatching composite — no provider stood in for.
     */
    ExternalCheckClient checkClient(List<String> allowlist) {
        def subsections = [
            github: ['api-url': wireMock.baseUrl(), repo: REPO],
            http: [allowlist: allowlist],
        ]
        def registry = CheckClientDiscovery.discover()
        CheckClientConfiguration.requireValidSubsections(subsections, registry, ConnectionProfiles.none())
        new ProviderDispatchingExternalCheckClient(
                registry,
                CheckProviderSeam.resolve(subsections, ConnectionProfiles.none()),
                { name -> Optional.of('tok') } as SecretsProvider)
    }

    /** A SonarQube quality gate: pending on the first poll, OK on the next (FR10's poll loop). */
    VerifyCheck.External qualityGate() {
        new VerifyCheck.External(
                'quality-gate', 'http',
                [
                    url: "https://127.0.0.1:${wireMock.httpsPort()}${QUALITY_GATE}?projectKey=widgets".toString(),
                    'pass-when': ['json-path': 'projectStatus.status', equals: 'OK'],
                    'pending-when': ['json-path': 'projectStatus.status', equals: 'IN_PROGRESS'],
                ],
                Duration.ofMillis(50), Duration.ofSeconds(30), VerifyCheck.TimeoutClass.QUALITY, [])
    }

    static VerifyCheck.External actionsRun() {
        new VerifyCheck.External(
                WORKFLOW, 'github', Duration.ofMillis(50), Duration.ofSeconds(30), VerifyCheck.TimeoutClass.QUALITY)
    }

    private void stubRun(String sha, int runId, String conclusion) {
        wireMock.stubFor(get(urlEqualTo("${RUNS_PATH}?head_sha=${sha}&per_page=100"))
                .willReturn(aResponse().withStatus(200).withBody("""
                    {"workflow_runs":[{"id":${runId},"head_sha":"${sha}","path":"${WORKFLOW}","run_attempt":1,
                     "status":"completed","conclusion":"${conclusion}"}]}
                    """)))
    }

    /** The red run's failure report: the adapter fetches jobs and log tails to build its findings. */
    private void stubFailedJobs() {
        wireMock.stubFor(get(urlEqualTo("/repos/${REPO}/actions/runs/${RED_RUN}/jobs?per_page=100"))
                .willReturn(aResponse().withStatus(200).withBody("""
                    {"jobs":[{"id":${RED_JOB},"name":"build","status":"completed","conclusion":"failure",
                     "steps":[{"name":"run-tests","status":"completed","conclusion":"failure"}]}]}
                    """)))
        wireMock.stubFor(get(urlEqualTo("/repos/${REPO}/actions/jobs/${RED_JOB}/logs"))
                .willReturn(aResponse().withStatus(200).withBody('boom: assertion failed')))
    }

    /** Two-state scenario, so the {@code pending-when} poll loop genuinely turns at least once. */
    private void stubQualityGate() {
        def url = urlEqualTo(QUALITY_GATE + '?projectKey=widgets')
        wireMock.stubFor(get(url).inScenario(SCENARIO).whenScenarioStateIs('Started')
                .willSetStateTo('analysed')
                .willReturn(aResponse().withStatus(200).withBody('{"projectStatus":{"status":"IN_PROGRESS"}}')))
        wireMock.stubFor(get(url).inScenario(SCENARIO).whenScenarioStateIs('analysed')
                .willReturn(aResponse().withStatus(200).withBody('{"projectStatus":{"status":"OK"}}')))
    }
}
