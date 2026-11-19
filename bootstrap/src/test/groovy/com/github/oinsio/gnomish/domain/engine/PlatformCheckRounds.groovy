package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.PinCheck
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.check.http.HttpExternalCheckClient
import com.github.oinsio.gnomish.adapter.check.http.HttpRunContext
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.adapter.scm.ScmConnectionReader
import com.github.oinsio.gnomish.app.BuiltInConnections
import com.github.oinsio.gnomish.app.BuiltInEntries
import com.github.oinsio.gnomish.app.CheckDispatch
import com.github.oinsio.gnomish.app.CheckRoundRoute
import com.github.oinsio.gnomish.app.ConnectionProfiles
import com.github.oinsio.gnomish.app.DescribedLaws
import com.github.oinsio.gnomish.app.ExecutorRegistry
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.gitobjects.CommitRequest
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.gitobjects.GitObjectsFixture
import com.github.oinsio.gnomish.gitobjects.ObjectId
import com.github.oinsio.gnomish.gitobjects.TreeEdit
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.InstantSource

/**
 * The run assembly's check rounds over {@link ThreeProviderPlatformFixture}'s platforms (task 4.2f of
 * migrate-verify-to-executor-contract), and the commits they judge: a law commit carrying the
 * workflow file and attempt commits on it that keep it, so the {@code github-actions} pin passes.
 *
 * <p>Test fixture; not production code, never PIT-mutated.
 */
class PlatformCheckRounds implements GitObjectsFixture {

    private GitObjects objects
    private ObjectId law

    /**
     * The engine's port for check rounds as the run assembly builds it: the built-ins' registry entries
     * over the checks' law — the {@code github-actions} connection admitted from the operator's
     * subsection, the {@code http} judgement under the operator's {@code in-process} scope — and the
     * check dispatch behind the pin-check of the law commit, routed outside a stage chain this spec
     * never runs.
     */
    StageExecutor of(List<VerifyCheck> checks, String apiUrl, List<String> inProcessEgress, ShellCommandCheckRunner shell,
            InstantSource clock) {
        def definition = new PipelineDefinition('1', new AutonomyLimits(3), [
            new StageDefinition('verify', 'p', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
            'i.md', checks, new AutonomyLimits(3), AdvancementMode.AUTO)
        ])
        SecretsProvider secrets = { name ->
            Optional.of('tok')
        } as SecretsProvider
        def admission = new BuiltInConnections([('github-actions'): ['api-url': apiUrl, repo: ThreeProviderPlatformFixture.REPO]],
        new ScmConnectionReader(ConnectionProfiles.none(), new OperatorEgress(ThreeProviderPlatformFixture.LOOPBACK_ALLOWLIST)), secrets).admit(definition)
        def equipment = new ProgramRoundEquipment(ProgramBounds.DEFAULTS, VirtualTimeEquipment.create(),
                TestChildEnvironment.hostAllowlist().programGrant(), Path.of('/tmp'), LastRegisteredCommit.NONE)
        def builtIns = new BuiltInEntries(new FilesExistCheckRunner(), shell,
                HttpExternalCheckClient.guarded(new OperatorEgress(inProcessEgress), secrets, HttpRunContext.none()), secrets, clock)
        def registry = ExecutorRegistry.of(DescribedLaws.connected((admission as BuiltInConnections.Admission.Admitted).connections()),
                definition, PipelineLaw.ofContent([:]), equipment, builtIns, null)
        def stages = { request ->
            throw new IllegalStateException('a verify chain runs no stage round')
        } as StageExecutor
        new CheckRoundRoute(stages, new CheckDispatch(registry, new PinCheck(objects, law)))
    }

    /** Seeds the law commit carrying the workflow file {@code workflow}. */
    void seed(Path root, String workflow) {
        def bare = seedBareRepo(root, [(workflow): "name: ci\n"])
        objects = openGitObjects(bare, root)
        law = objects.resolveRef('refs/heads/base').orElseThrow()
    }

    /** An attempt commit on the law commit that keeps the workflow and adds {@code work}; its sha. */
    String attempt(String work) {
        objects.commit(new CommitRequest('refs/heads/gnomish/' + work, Optional.empty(), law,
                [
                    new TreeEdit.PutFile('src/' + work + '.txt', work.getBytes(StandardCharsets.UTF_8))
                ], metadata())).hex()
    }
}
