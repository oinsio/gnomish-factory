package com.github.oinsio.gnomish.app

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED

import com.github.oinsio.gnomish.adapter.scm.ScmConnectionReader
import com.github.oinsio.gnomish.app.conformance.BuiltInCandidate
import com.github.oinsio.gnomish.app.conformance.CaseBinding
import com.github.oinsio.gnomish.app.conformance.CaseVerdict
import com.github.oinsio.gnomish.app.conformance.ConformanceKit
import com.github.oinsio.gnomish.app.conformance.KitCase
import com.github.oinsio.gnomish.app.conformance.KitReport
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.app.workspace.fake.AttemptCommitWorkspaces
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.httpegress.HttpCheckExchange
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import com.github.tomakehurst.wiremock.WireMockServer
import java.net.http.HttpRequest
import java.nio.file.Path
import java.time.Duration
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR2, FR8, FR13 of migrate-verify-to-executor-contract (design D14, D18, "The conformance kit
 * drives a built-in through its entry"): the three remote verdict built-ins — {@code http}, {@code
 * github-actions} and {@code scm-status} — run through the conformance kit by their registry entries,
 * every applicable case passing, the two pending cases included: each start answers {@code pending}
 * against a stateful fake that answers the verdict on a later poll, and on every poll after it (the
 * re-poll case, FR11, FR12; design D11). The two SCM built-ins are bound per case to an attempt-commit
 * workspace, each pending case to a commit of its own, so each stateful answer is reached by that case
 * only; {@code http} reads a scripted exchange whose pending endpoint answers {@code RUNNING} to the
 * first and the third call — each pending case's start — and {@code DONE} to every other.
 */
class RemoteBuiltInConformanceSpec extends Specification {

    private static final String DONE = 'a' * 40
    private static final String WAITING = 'b' * 40
    private static final String REPOLL = 'c' * 40
    private static final String HEALTH = 'https://ci.example.invalid/health'
    private static final String JOB = 'https://ci.example.invalid/job'

    @Shared
    WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort())

    @TempDir
    Path scratch

    List<Duration> waits = []

    def setupSpec() {
        server.start()
        server.stubFor(get(urlEqualTo("/repos/acme/widgets/commits/${DONE}/status?per_page=100"))
                .willReturn(json(statuses(DONE, 'success'))))
        [WAITING, REPOLL].each { String sha ->
            String status = "/repos/acme/widgets/commits/${sha}/status?per_page=100"
            server.stubFor(get(urlEqualTo(status)).inScenario("status-${sha}").whenScenarioStateIs(STARTED)
                    .willSetStateTo('reported').willReturn(json(statuses(sha, 'pending'))))
            server.stubFor(get(urlEqualTo(status)).inScenario("status-${sha}").whenScenarioStateIs('reported')
                    .willReturn(json(statuses(sha, 'success'))))
            server.stubFor(get(urlEqualTo(runsPath(sha))).inScenario("runs-${sha}").whenScenarioStateIs(STARTED)
                    .willSetStateTo('finished').willReturn(json(runs(sha, 'in_progress', null))))
            server.stubFor(get(urlEqualTo(runsPath(sha))).inScenario("runs-${sha}").whenScenarioStateIs('finished')
                    .willReturn(json(runs(sha, 'completed', 'success'))))
        }
        [DONE, WAITING, REPOLL].each { String sha ->
            server.stubFor(get(urlEqualTo("/repos/acme/widgets/commits/${sha}/check-runs?per_page=100"))
            .willReturn(aResponse().withStatus(404)))
        }
        server.stubFor(get(urlEqualTo(runsPath(DONE))).willReturn(json(runs(DONE, 'completed', 'success'))))
    }

    def cleanupSpec() {
        server.stop()
    }

    private static String runsPath(String sha) {
        "/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=${sha}&per_page=100"
    }

    private static json(String body) {
        aResponse().withStatus(200).withHeader('Content-Type', 'application/json').withBody(body)
    }

    private static String statuses(String sha, String state) {
        """{"state":"${state}","sha":"${sha}","total_count":1,
           "statuses":[{"id":1,"context":"build","state":"${state}","description":null}]}"""
    }

    private static String runs(String sha, String status, String conclusion) {
        """{"workflow_runs":[{"id":1,"head_sha":"${sha}","path":"ci.yml","run_attempt":1,"status":"${status}",
           "conclusion":${conclusion == null ? 'null' : '"' + conclusion + '"'},"html_url":"https://github.example/runs/1"}]}"""
    }

    /** The scripted {@code http} exchange: the health endpoint is up; the job runs at each pending start, then is done. */
    private static HttpCheckExchange exchange() {
        int jobCalls = 0
        return { HttpRequest request ->
            if (request.uri().toString() != JOB) {
                return new HttpCheckExchange.Response(200, 'ok')
            }
            jobCalls++
            new HttpCheckExchange.Response(200, jobCalls in [1, 3] ? '{"state":"RUNNING"}' : '{"state":"DONE"}')
        } as HttpCheckExchange
    }

    private KitReport run(VerifyCheck.Executor check, Map<String, ?> settings, Map<String, ?> pendingSettings) {
        def definition = new PipelineDefinition('1', new AutonomyLimits(3), [
            new StageDefinition('build', 'p', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
            'i.md', [check], new AutonomyLimits(3), AdvancementMode.AUTO)
        ])
        def connection = [('api-url'): server.baseUrl(), repo: 'acme/widgets']
        def admitted = new BuiltInConnections(['scm-status': connection, 'github-actions': connection],
        new ScmConnectionReader(ConnectionProfiles.none(), new OperatorEgress(['loopback'])), { String n ->
            Optional.of('token')
        } as SecretsProvider).admit(definition)
        def described = DescribedLaws.connected((admitted as BuiltInConnections.Admission.Admitted).connections())
        def entry = BuiltInEntriesFixture.scripted(new VirtualClock(),
                [GNOMISH_SCM_STATUS_TOKEN: 'token', GNOMISH_GITHUB_ACTIONS_TOKEN: 'token'], exchange())
                .of(definition, described).find {
                    it.name().value() == check.executorName()
                }
        def remote = check.executorName() in [
            'github-actions',
            'scm-status'
        ]
        def binding = { KitCase kase, Path workingCopy ->
            entry.executor(check, CheckRef.of(0, check), remote
            ? AttemptCommitWorkspaces.at(kase == KitCase.PENDING_POLL ? WAITING : kase == KitCase.PENDING_REPOLL ? REPOLL : DONE)
            : new DirectoryWorkspace(workingCopy))
        } as CaseBinding
        new ConformanceKit(pendingSettings, { Duration d ->
            waits << d
        } as Sleeper)
        .run(new BuiltInCandidate(entry.describe(), binding, settings, scratch))
    }

    private static Set<KitCase> notApplicable(KitReport report) {
        report.verdicts().findAll {
            it.value instanceof CaseVerdict.NotApplicable
        }.keySet()
    }

    def "FR1, FR2, FR8: #name passes every applicable case through its entry, its pending start ended by a poll"() {
        when:
        def report = run(check, settings, pendingSettings)

        then: 'no case failed; the verdict and both pending cases passed'
        report.failures() == 0
        report.verdicts()[KitCase.VERDICT] instanceof CaseVerdict.Passed
        report.verdicts()[KitCase.PENDING_POLL] instanceof CaseVerdict.Passed
        report.verdicts()[KitCase.PENDING_REPOLL] instanceof CaseVerdict.Passed
        report.verdicts()[KitCase.SETTINGS_SCHEMA] instanceof CaseVerdict.Passed
        report.verdicts()[KitCase.UNKNOWN_ROLE] instanceof CaseVerdict.Passed

        and: 'only the wire cases and the roles a verdict built-in does not list are not applicable'
        notApplicable(report) == (KitCase.values().findAll {
            it.wireOnly()
        } + [
            KitCase.TRANSFORM,
            KitCase.DECISION,
            KitCase.MALFORMED_ANSWER
        ]) as Set

        and: 'the pending start was polled once to its verdict, with no wait before the first poll'
        waits == []

        where:
        name | check | settings | pendingSettings
        'http' | VerifyChecks.external('probe', 'http', [url: HEALTH], Duration.ofSeconds(1), Duration.ofMinutes(1),
        VerifyCheck.TimeoutClass.QUALITY, []) | [url: HEALTH] | [url: JOB, ('pending-when'): [('json-path'): 'state', equals: 'RUNNING']]
        'github-actions' | VerifyChecks.external('ci.yml', 'github-actions', Duration.ofSeconds(1), Duration.ofMinutes(1),
                VerifyCheck.TimeoutClass.QUALITY) | [:] | [:]
        'scm-status' | scmStatus() | [:] | [:]
    }

    private static VerifyCheck.Executor scmStatus() {
        new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, 'scm-status', 'ci', [:], Duration.ofSeconds(1),
        Duration.ofMinutes(1), VerifyCheck.TimeoutClass.QUALITY, [], VerifyCheck.VerifyIn.FRESH_BOX,
        Threshold.ERROR, ['build'])
    }
}
