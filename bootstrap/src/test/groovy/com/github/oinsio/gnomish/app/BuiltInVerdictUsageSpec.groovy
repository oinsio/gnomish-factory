package com.github.oinsio.gnomish.app

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig

import com.github.oinsio.gnomish.adapter.agent.program.ProgramResultMapping
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.adapter.scm.ScmConnectionReader
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.app.workspace.fake.AttemptCommitWorkspaces
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.ExecutorRole
import com.github.oinsio.gnomish.domain.engine.ParticipantUsage
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.UsageFold
import com.github.oinsio.gnomish.domain.engine.UsageProvenance
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
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
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorName
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Usage
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.httpegress.HttpCheckExchange
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.tomakehurst.wiremock.WireMockServer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NFR-C1 of migrate-verify-to-executor-contract (design D13): each of the five verdict built-ins,
 * driven through its registry entry, completes a check with usage provenance {@code REPORTED} and no
 * per-model figure — the one known zero, {@link Usage#knownZero()} — and its describe declares that
 * it reports usage; a task whose checks are the five folds to a complete cost. The two SCM built-ins
 * answer from a WireMock server on loopback, {@code http} from a scripted exchange, {@code shell}
 * runs {@code true}, and {@code files-exist} finds its path.
 */
class BuiltInVerdictUsageSpec extends Specification {

    private static final String SHA = 'a' * 40

    @Shared
    WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort())

    @TempDir
    Path workingCopy

    def setupSpec() {
        server.start()
        server.stubFor(get(urlEqualTo("/repos/acme/widgets/commits/${SHA}/status?per_page=100"))
                .willReturn(aResponse().withStatus(200).withHeader('Content-Type', 'application/json').withBody(
                        """{"state":"success","sha":"${SHA}","total_count":1,
                   "statuses":[{"id":1,"context":"build","state":"success","description":null}]}""")))
        server.stubFor(get(urlEqualTo("/repos/acme/widgets/commits/${SHA}/check-runs?per_page=100"))
                .willReturn(aResponse().withStatus(404)))
        server.stubFor(get(urlEqualTo("/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=${SHA}&per_page=100"))
                .willReturn(aResponse().withStatus(200).withHeader('Content-Type', 'application/json').withBody(
                        """{"workflow_runs":[{"id":1,"head_sha":"${SHA}","path":"ci.yml","run_attempt":1,
                   "status":"completed","conclusion":"success","html_url":"https://github.example/runs/1"}]}""")))
    }

    def cleanupSpec() {
        server.stop()
    }

    private static VerifyCheck.Executor scm(String executor, String checkId) {
        new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, executor, checkId, [:], Duration.ofSeconds(1),
        Duration.ofMinutes(1), VerifyCheck.TimeoutClass.QUALITY, [], VerifyCheck.VerifyIn.FRESH_BOX,
        Threshold.ERROR, ['build'])
    }

    private List<VerifyCheck.Executor> checks() {
        [
            VerifyChecks.builtin('files', [files: ['present.txt']]),
            VerifyChecks.command('true'),
            VerifyChecks.external('probe', 'http', [url: 'https://ci.example.invalid/health'], Duration.ofSeconds(1),
            Duration.ofMinutes(1), VerifyCheck.TimeoutClass.QUALITY, []),
            VerifyChecks.external('ci.yml', 'github-actions', Duration.ofSeconds(1), Duration.ofMinutes(1),
            VerifyCheck.TimeoutClass.QUALITY),
            scm('scm-status', 'ci')
        ]
    }

    private ExecutorRegistry registry(PipelineDefinition definition) {
        def connection = [('api-url'): server.baseUrl(), repo: 'acme/widgets']
        def admitted = new BuiltInConnections(['scm-status': connection, 'github-actions': connection],
        new ScmConnectionReader(ConnectionProfiles.none(), new OperatorEgress(['loopback'])), { String n ->
            Optional.of('token')
        } as SecretsProvider).admit(definition)
        def clock = new VirtualClock()
        def equipment = new ProgramRoundEquipment(ProgramBounds.DEFAULTS, VirtualTimeEquipment.on(clock),
                TestChildEnvironment.hostAllowlist().programGrant(), Path.of('/tmp'), LastRegisteredCommit.NONE)
        ExecutorRegistry.of(DescribedLaws.connected((admitted as BuiltInConnections.Admission.Admitted).connections()),
                definition, PipelineLaw.ofContent([:]), equipment, BuiltInEntriesFixture.scripted(clock,
                [GNOMISH_SCM_STATUS_TOKEN: 'token', GNOMISH_GITHUB_ACTIONS_TOKEN: 'token'], { request ->
                    new HttpCheckExchange.Response(200, 'ok')
                } as HttpCheckExchange), null, AgentEntryFixture.idle(equipment))
    }

    private com.github.oinsio.gnomish.domain.engine.port.Workspace workspaceOf(VerifyCheck.Executor check) {
        check.executorName() in [
            'github-actions',
            'scm-status'
        ] ? AttemptCommitWorkspaces.at(SHA) : new DirectoryWorkspace(workingCopy)
    }

    private static ExecutorRequest verdict(VerifyCheck.Executor check) {
        new ExecutorRequest(Role.VERDICT, new Workspace('.'), new Policy.ReadOnly(), RoleInputs.verdict('c'),
                ExecutorContractJson.settingsTree(check.settings()), com.github.oinsio.gnomish.executorcontract.Bounds.none(),
                [], null)
    }

    // Scenario: verification/verdict-executors / Built-in verdict usage keeps the task cost complete
    def "NFR-C1: each of the five built-ins completes its check with the known zero, and the task's usage fold stays complete"() {
        given:
        Files.createFile(workingCopy.resolve('present.txt'))
        def definition = new PipelineDefinition('1', new AutonomyLimits(3), [
            new StageDefinition('build', 'p', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
            'i.md', checks(), new AutonomyLimits(3), AdvancementMode.AUTO)
        ])
        def registry = registry(definition)

        when: 'each check is bound through its entry and run once'
        List<ParticipantUsage> entries = []
        Map<String, Object> outcomes = [:]
        checks().eachWithIndex { VerifyCheck.Executor check, int vote ->
            def entry = registry.resolve(new ExecutorName(check.executorName()))
            def result = entry.executor(check, CheckRef.of(0, check), workspaceOf(check)).start(verdict(check))
            outcomes[check.executorName()] = [status: result.status(), usage: result.usage(), reports: entry.describe().reportsUsage()]
            def usage = ProgramResultMapping.toEngine(check.executorName(), Role.VERDICT,
                    new AttemptKey('TASK-1', 'build', 0), result, []).usage()
            entries << new ParticipantUsage(ExecutorRole.VERDICT, check.executorName(), vote, usage.provenance(),
                    usage.wallTime(), usage.tools(), usage.byModel())
        }
        def round = AttemptRecords.identified(AttemptRecords.TASK_ID, AttemptRecords.STAGE, null, 1,
                AttemptRecord.Result.PASSED, Instant.EPOCH, [], entries, [], Stop.none())

        then: 'every built-in completed, reported the known zero, and declares that it reports usage'
        outcomes.keySet() == [
            'files-exist',
            'shell',
            'http',
            'github-actions',
            'scm-status'
        ] as Set
        outcomes.values().every {
            it.status == Status.COMPLETED && it.usage == Usage.knownZero() && it.reports
        }

        and: 'each usage entry is REPORTED with no per-model figure, and the fold is complete'
        entries.every {
            it.provenance() == UsageProvenance.REPORTED && it.byModel().isEmpty()
        }
        UsageFold.foldAll([round]).cost().complete()
    }
}
