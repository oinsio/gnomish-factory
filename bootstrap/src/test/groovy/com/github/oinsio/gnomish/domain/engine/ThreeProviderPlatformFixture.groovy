package com.github.oinsio.gnomish.domain.engine

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.adapter.check.LoopbackTlsFixture
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import java.nio.file.Path
import java.time.Duration
import java.time.InstantSource
import javax.net.ssl.SSLContext

/**
 * The two third-party platforms {@link ThreeProviderVerifyChainSpec} verifies against, and the
 * production wiring that reaches them: one WireMock serving a GitHub Actions API over plain http and
 * a SonarQube quality gate over TLS, plus the run assembly's check rounds over them: the built-ins'
 * registry entries, the {@code github-actions} connection admitted from the operator's subsection,
 * and the check dispatch behind the pin-check of a law commit whose workflow both attempt commits
 * keep (task 4.2f of migrate-verify-to-executor-contract).
 *
 * <p>Extracted from the spec so the acceptance claim and its staging are read separately — the
 * staging is the uninteresting half, and the file-size rule is the reminder.
 */
class ThreeProviderPlatformFixture {

    static final String REPO = 'acme/widgets'
    static final String WORKFLOW = 'ci.yml'
    static final String QUALITY_GATE = '/api/qualitygates/project_status'
    static final String STUCK_QUALITY_GATE = '/api/qualitygates/stuck_status'
    static final String RUNS_PATH = "/repos/${REPO}/actions/workflows/${WORKFLOW}/runs"
    /** The operator's in-process egress scope granting the WireMock stub: the {@code loopback} class word. */
    static final List<String> LOOPBACK_ALLOWLIST = ['loopback']

    private static final String SCENARIO = 'quality-gate'
    private static final int RED_RUN = 2
    private static final int RED_JOB = 20

    WireMockServer wireMock
    private SSLContext previousSslContext
    private final PlatformCheckRounds attempts = new PlatformCheckRounds()

    /** The attempt commit whose workflow run is green. */
    String greenSha

    /** The attempt commit whose workflow run is red. */
    String redSha

    /** Starts both listeners and stubs both platforms; TLS is trusted for the fixture's lifetime. */
    void start(Path tempDir) {
        attempts.seed(tempDir.resolve('law'), WORKFLOW)
        greenSha = attempts.attempt('green')
        redSha = attempts.attempt('red')
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
        stubRun(greenSha, 1, 'success')
        stubRun(redSha, RED_RUN, 'failure')
        stubFailedJobs()
        stubQualityGate()
        stubStuckQualityGate()
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

    /** The run assembly's check rounds over the two platforms; see {@link PlatformCheckRounds#of}. */
    StageExecutor checkRounds(List<VerifyCheck> checks, List<String> inProcessEgress, ShellCommandCheckRunner shell,
            InstantSource clock) {
        attempts.of(checks, wireMock.baseUrl(), inProcessEgress, shell, clock)
    }

    /** A SonarQube quality gate: pending on the first poll, OK on the next (FR10's poll loop). */
    VerifyCheck.Executor qualityGate() {
        VerifyChecks.external(
                'quality-gate', 'http',
                [
                    url: "https://127.0.0.1:${wireMock.httpsPort()}${QUALITY_GATE}?projectKey=widgets".toString(),
                    'pass-when': ['json-path': 'projectStatus.status', equals: 'OK'],
                    'pending-when': ['json-path': 'projectStatus.status', equals: 'IN_PROGRESS'],
                ],
                Duration.ofMillis(50), Duration.ofSeconds(30), VerifyCheck.TimeoutClass.QUALITY, [])
    }

    /**
     * The same SonarQube quality gate on an endpoint that never leaves {@code IN_PROGRESS}: the
     * poll loop keeps waiting until the (deliberately tiny) timeout elapses, so the http provider's
     * timeout really is driven end to end and classified by the check's own {@code timeoutClass}.
     */
    VerifyCheck.Executor stuckQualityGate(VerifyCheck.TimeoutClass timeoutClass) {
        VerifyChecks.external(
                'stuck-gate', 'http',
                [
                    url: "https://127.0.0.1:${wireMock.httpsPort()}${STUCK_QUALITY_GATE}".toString(),
                    'pass-when': ['json-path': 'projectStatus.status', equals: 'OK'],
                    'pending-when': ['json-path': 'projectStatus.status', equals: 'IN_PROGRESS'],
                ],
                Duration.ofMillis(20), Duration.ofMillis(120), timeoutClass, [])
    }

    static VerifyCheck.Executor actionsRun() {
        VerifyChecks.external(WORKFLOW, BuiltInExecutors.GITHUB_ACTIONS, Duration.ofMillis(50), Duration.ofSeconds(30),
                VerifyCheck.TimeoutClass.QUALITY)
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

    /** A quality gate stuck in analysis: every poll answers pending, so the loop can only time out. */
    private void stubStuckQualityGate() {
        wireMock.stubFor(get(urlEqualTo(STUCK_QUALITY_GATE))
                .willReturn(aResponse().withStatus(200).withBody('{"projectStatus":{"status":"IN_PROGRESS"}}')))
    }
}
