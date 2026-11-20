package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.check.FilesExistExecutor
import com.github.oinsio.gnomish.adapter.check.ShellExecutor
import com.github.oinsio.gnomish.adapter.check.http.HttpExecutor
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.adapter.scm.ScmConnectionReader
import com.github.oinsio.gnomish.adapter.scm.status.ScmStatusExecutor
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
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
import com.github.oinsio.gnomish.executorcontract.ExecutorName
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR13 of migrate-verify-to-executor-contract (design D18 as amended 2026-11-10): the run's
 * {@link ExecutorRegistry} holds an entry per name — its describe and its long-lived collaborators —
 * and binds the executor at resolution. Each verdict built-in a check names gets an entry carrying
 * its own describe; a resolution binds a new executor to the check and the working copy it is given,
 * so nothing bound is kept; a built-in no check names gets no entry, and an SCM built-in's entry
 * exists only over the connection run assembly admitted.
 */
class ExecutorRegistryEntrySpec extends Specification {

    @TempDir
    Path workingCopy

    def clock = new VirtualClock()

    private static final VerifyCheck.Executor FILES = VerifyChecks.builtin('files', [files: ['a']])
    private static final VerifyCheck.Executor SHELL = VerifyChecks.command('true')
    private static final VerifyCheck.Executor HTTP = VerifyChecks.external('probe', 'http',
    [url: 'http://127.0.0.1/'], Duration.ofSeconds(1), Duration.ofMinutes(1), VerifyCheck.TimeoutClass.QUALITY, [])
    private static final VerifyCheck.Executor STATUS = new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR,
    'scm-status', 'ci', [:], Duration.ofSeconds(1), Duration.ofMinutes(1), VerifyCheck.TimeoutClass.QUALITY, [],
    VerifyCheck.VerifyIn.FRESH_BOX, Threshold.ERROR, ['build'])

    private static PipelineDefinition law(List<VerifyCheck> checks) {
        new PipelineDefinition('1', new AutonomyLimits(3), [
            new StageDefinition('build', 'p', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
            'i.md', checks, new AutonomyLimits(3), AdvancementMode.AUTO)
        ])
    }

    private ExecutorRegistry registry(List<VerifyCheck> checks, DescribedLaw described = DescribedLaws.none()) {
        def equipment = new ProgramRoundEquipment(ProgramBounds.DEFAULTS, VirtualTimeEquipment.on(clock),
                TestChildEnvironment.hostAllowlist().programGrant(), Path.of('/tmp'), LastRegisteredCommit.NONE)
        ExecutorRegistry.of(described, law(checks), PipelineLaw.ofContent([:]), equipment,
        BuiltInEntriesFixture.of(clock, [GNOMISH_SCM_STATUS_TOKEN: 't']), null)
    }

    private static String registered(ExecutorRegistry registry) {
        try {
            registry.resolve(new ExecutorName('none-such'))
            return null
        } catch (IllegalStateException e) {
            return e.message
        }
    }

    def "FR13: a law whose checks name no built-in registers the command built-in only"() {
        expect:
        registered(registry([])).endsWith('registered: [command]')
    }

    def "FR1, FR13: the #name entry carries its built-in's describe and binds a new #type to each resolution"() {
        given:
        def entry = registry([FILES, SHELL, HTTP]).resolve(new ExecutorName(name))
        def workspace = new DirectoryWorkspace(workingCopy)

        when:
        def first = entry.executor(check, CheckRef.of(0, check), workspace)
        def second = entry.executor(check, CheckRef.of(0, check), workspace)

        then:
        entry.name() == new ExecutorName(name)
        entry.describe() == describe
        type.isInstance(first)
        type.isInstance(second)
        !first.is(second)

        where:
        name | check | type || describe
        'files-exist' | FILES | FilesExistExecutor || FilesExistExecutor.describeAnswer()
        'shell' | SHELL | ShellExecutor || ShellExecutor.describeAnswer()
        'http' | HTTP | HttpExecutor || HttpExecutor.describeAnswer()
    }

    // FR1, FR6: a provider: http check — the loader maps it to executor http with the EXTERNAL token
    //     (VerifyCheckMapperSpec) — resolves from the executor registry and binds the http built-in,
    //     the same resolution path every verdict executor takes; no check-provider registry exists
    //     (task 5.2).
    // Scenario: verification/http-check-provider / http provider resolves like any other
    def "FR1, FR6: an external provider: http check resolves from the executor registry like any verdict executor"() {
        given:
        def entry = registry([HTTP]).resolve(new ExecutorName(HTTP.executorName()))

        expect:
        HTTP.token() == VerifyCheck.Token.EXTERNAL
        entry.name() == new ExecutorName('http')
        entry.executor(HTTP, CheckRef.of(0, HTTP), new DirectoryWorkspace(workingCopy)) instanceof HttpExecutor
    }

    def "FR13: an scm-status check gets its entry only over the connection run assembly admitted"() {
        given:
        def admitted = new BuiltInConnections(['scm-status': [('api-url'): 'http://127.0.0.1:3000', repo: 'acme/widgets']],
        new ScmConnectionReader(ConnectionProfiles.none(), new OperatorEgress(['loopback'])), { String n ->
            Optional.of('t')
        } as SecretsProvider).admit(law([STATUS]))
        def connected = DescribedLaws.connected((admitted as BuiltInConnections.Admission.Admitted).connections())

        when:
        def entry = registry([STATUS], connected).resolve(new ExecutorName('scm-status'))

        then:
        entry.describe().egress() == ['127.0.0.1']
        entry.executor(STATUS, CheckRef.of(0, STATUS), AttemptCommitWorkspaces.at('abc')) instanceof ScmStatusExecutor

        and: 'a run that admitted no connection — one that runs no stage — registers none'
        registered(registry([STATUS])).endsWith('registered: [command]')
    }

    def "FR13: a check entry binds no stage round, and a stage entry no check round"() {
        given:
        def registry = registry([FILES])

        when:
        registry.resolve(new ExecutorName('files-exist')).rounds()

        then:
        def stage = thrown(IllegalStateException)
        stage.message == "executor 'files-exist' runs check rounds only, not a stage's"

        when:
        registry.resolve(new ExecutorName('command')).executor(FILES, CheckRef.of(0, FILES), new DirectoryWorkspace(workingCopy))

        then:
        def check = thrown(IllegalStateException)
        check.message == "executor 'command' runs no check round in this run"

        and: 'the stage entry binds its round executor to an open round\'s environment'
        registry.resolve(new ExecutorName('command')).rounds().executor(Stub(TaskExecutionEnvironment)) != null
    }
}
