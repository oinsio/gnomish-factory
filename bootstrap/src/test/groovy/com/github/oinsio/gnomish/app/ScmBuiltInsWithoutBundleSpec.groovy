package com.github.oinsio.gnomish.app

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig

import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.adapter.plugin.GithubArtifact
import com.github.oinsio.gnomish.adapter.scm.ScmConnectionReader
import com.github.oinsio.gnomish.adapter.tracker.TrackerAdapterDiscovery
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.workspace.fake.AttemptCommitWorkspaces
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.executorcontract.Bounds
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorName
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.tomakehurst.wiremock.WireMockServer
import java.nio.file.Path
import java.time.Duration
import spock.lang.Shared
import spock.lang.Specification

/**
 * FR13, FR14, M5 of migrate-verify-to-executor-contract (design D4, D18): the {@code github-actions}
 * and {@code scm-status} built-ins are core's own. With the github vendor bundle's registrations
 * hidden from discovery ({@link GithubArtifact}), the github tracker is unavailable, while a stage
 * declaring one check of each built-in has both resolved from the run's executor registry — which
 * reads no service registration — and run against a WireMock platform on loopback, each to a
 * completed verdict.
 */
class ScmBuiltInsWithoutBundleSpec extends Specification {

    private static final String SHA = 'b' * 40

    @Shared
    WireMockServer platform = new WireMockServer(wireMockConfig().dynamicPort())

    def setupSpec() {
        platform.start()
        platform.stubFor(get(urlEqualTo("/repos/acme/widgets/commits/${SHA}/status?per_page=100"))
                .willReturn(aResponse().withStatus(200).withHeader('Content-Type', 'application/json').withBody(
                        """{"state":"success","sha":"${SHA}","total_count":1,
                   "statuses":[{"id":1,"context":"build","state":"success","description":null}]}""")))
        platform.stubFor(get(urlEqualTo("/repos/acme/widgets/commits/${SHA}/check-runs?per_page=100"))
                .willReturn(aResponse().withStatus(404)))
        platform.stubFor(get(urlEqualTo("/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=${SHA}&per_page=100"))
                .willReturn(aResponse().withStatus(200).withHeader('Content-Type', 'application/json').withBody(
                        """{"workflow_runs":[{"id":1,"head_sha":"${SHA}","path":"ci.yml","run_attempt":1,
                   "status":"completed","conclusion":"success","html_url":"https://github.example/runs/1"}]}""")))
    }

    def cleanupSpec() {
        platform.stop()
    }

    private static List<VerifyCheck.Executor> checks() {
        [
            VerifyChecks.external('ci.yml', 'github-actions', Duration.ofSeconds(1), Duration.ofMinutes(1),
            VerifyCheck.TimeoutClass.QUALITY),
            new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, 'scm-status', 'ci', [:], Duration.ofSeconds(1),
            Duration.ofMinutes(1), VerifyCheck.TimeoutClass.QUALITY, [], VerifyCheck.VerifyIn.FRESH_BOX,
            Threshold.ERROR, ['build'])
        ]
    }

    private ExecutorRegistry registry(PipelineDefinition definition) {
        def connection = [('api-url'): platform.baseUrl(), repo: 'acme/widgets']
        def admitted = new BuiltInConnections(['scm-status': connection, 'github-actions': connection],
        new ScmConnectionReader(ConnectionProfiles.none(), new OperatorEgress(['loopback'])), { String n ->
            Optional.of('token')
        } as SecretsProvider).admit(definition)
        def clock = new VirtualClock()
        def equipment = new ProgramRoundEquipment(ProgramBounds.DEFAULTS, VirtualTimeEquipment.on(clock),
                TestChildEnvironment.hostAllowlist().programGrant(), Path.of('/tmp'), LastRegisteredCommit.NONE)
        ExecutorRegistry.of(DescribedLaws.connected((admitted as BuiltInConnections.Admission.Admitted).connections()),
                definition, PipelineLaw.ofContent([:]), equipment,
                BuiltInEntriesFixture.of(clock, [GNOMISH_SCM_STATUS_TOKEN: 'token', GNOMISH_GITHUB_ACTIONS_TOKEN: 'token']),
                null, AgentEntryFixture.idle(equipment))
    }

    // Scenario: verification/verdict-executors / Removing the vendor bundle keeps the SCM built-ins
    def "FR13: without the github vendor bundle the tracker is unavailable, and a stage's github-actions and scm-status checks resolve and run"() {
        given: 'the github bundle hidden from discovery, and a stage declaring one check of each SCM built-in'
        def withoutGithub = GithubArtifact.hiddenFrom(getClass().classLoader)
        def definition = new PipelineDefinition('1', new AutonomyLimits(3), [
            new StageDefinition('build', 'p', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
            'i.md', checks(), new AutonomyLimits(3), AdvancementMode.AUTO)
        ])

        when: 'discovery runs without the bundle, and each check is resolved from the registry and started'
        def trackers = TrackerAdapterDiscovery.discover(withoutGithub)
        def registry = registry(definition)
        Map<String, Status> statuses = checks().collectEntries { VerifyCheck.Executor check ->
            def entry = registry.resolve(new ExecutorName(check.executorName()))
            def executor = entry.executor(check, CheckRef.of(0, check), AttemptCommitWorkspaces.at(SHA))
            [(check.executorName()): executor.start(verdict(check)).status()]
        }

        then: 'the github tracker is unavailable, while the bundle-present discovery still finds it'
        !trackers.containsKey('github')
        TrackerAdapterDiscovery.discover(getClass().classLoader).containsKey('github')

        and: 'both SCM built-ins ran to a completed verdict against the platform'
        statuses == ['github-actions': Status.COMPLETED, 'scm-status': Status.COMPLETED]
        platform.allServeEvents*.request*.url.any {
            it.contains('/actions/workflows/ci.yml/runs')
        }
        platform.allServeEvents*.request*.url.any {
            it.contains("/commits/${SHA}/status")
        }
    }

    private static ExecutorRequest verdict(VerifyCheck.Executor check) {
        new ExecutorRequest(Role.VERDICT, new Workspace('.'), new Policy.ReadOnly(), RoleInputs.verdict('c'),
                ExecutorContractJson.settingsTree(check.settings()), Bounds.none(), [], null)
    }
}
