package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.EgressProperties
import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.program.DescribedProgram
import com.github.oinsio.gnomish.adapter.agent.program.PendingRuns
import com.github.oinsio.gnomish.adapter.agent.program.PendingScope
import com.github.oinsio.gnomish.adapter.agent.program.ProgramCheckBinding
import com.github.oinsio.gnomish.adapter.agent.program.ProgramExecutor
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.app.describe.DescribeOutcome
import com.github.oinsio.gnomish.app.describe.DescribePreflight
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.AttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.executorcontract.Describe
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ChildEnvValues
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.StderrPolicy
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.GuardImageAvailability
import com.github.oinsio.gnomish.subprocess.Drain
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The real box a credential-delivery spec reads (NFR-S1 of define-executor-contract and of
 * migrate-verify-to-executor-contract): a project and its task over the production {@link
 * ContainerRunSupportFactory}, the variable names one exec's child received, and the allowlist a box
 * guard admits. Test fixture base; not production code.
 */
abstract class CredentialDeliverySpecBase extends Specification implements BareGitRepoFixture {

    static final String VALUE = 'acme-delivered-value'

    static final ChildEnvValues SECRETS = { String name ->
        Optional.ofNullable([ACME_API_KEY: VALUE, GNOMISH_GITHUB_ACTIONS_TOKEN: 'actions-token-value'][name])
    } as ChildEnvValues

    @TempDir
    Path tempDir

    String taskId

    Path project

    def cleanup() {
        if (taskId != null) {
            ContainerE2eDocker.removeTaskObjects(taskId)
            ['', '-v'].each {
                Path.of(System.getProperty('java.io.tmpdir'), 'gnomish-guard', taskId + it).toFile().deleteDir()
            }
        }
    }

    protected Path guardFile(String suffix) {
        Path.of(System.getProperty('java.io.tmpdir'), 'gnomish-guard', taskId + suffix, 'allowlist.json')
    }

    protected ContainerRunSupport startTask(PipelineDefinition law, ChildEnvAllowlist installation) {
        startTask(law, installation, FakeAgentSandboxImage.ensureBuilt('plain-round'))
    }

    /** {@link #startTask(PipelineDefinition, ChildEnvAllowlist)} on the box image {@code image}. */
    protected ContainerRunSupport startTask(PipelineDefinition law, ChildEnvAllowlist installation, String image) {
        def sandbox = new SandboxProperties(image, null, null, null, [], false, null, null, null, null)
        project = initWorkingRepo(tempDir, 'program-credential-project')
        Files.createDirectories(project.resolve('.gnomish'))
        Files.writeString(project.resolve('.gnomish/instructions.md'), 'build it\n')
        commitAll(project)
        taskId = "CTN-GRANT-${System.nanoTime() % 100000}"
        def support = ContainerSupportFixture.directFor(project, taskId, [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), law.stages())
        ], sandbox, new FactoryProperties(null, null, null, null, null, null, null, null, new EgressProperties(['example.com'], [])), ClaimEpochSource.NONE, installation, law)
        support.taskRepository().createTask(
                new TaskContext(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body'), List.<Decision> of()),
                TaskStart.commit(project, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('work'))
        addRemote(project, 'origin', 'https://example.invalid/org/program-credential-project.git')
        support
    }

    /** The variable names one exec's child received, its factory-set layer {@code env}. */
    protected static Set<String> names(TaskExecutionEnvironment box, Map<String, String> env) {
        output(box, [
            'sh',
            '-c',
            'env | cut -d= -f1'
        ], env).readLines().findAll {
            it
        } as Set
    }

    protected static String output(TaskExecutionEnvironment box, List<String> argv, Map<String, String> env) {
        def handle = box.exec(new ExecCommand(argv, env, null, StderrPolicy.MERGED))
        def drain = Drain.capped(handle.output(), 'delivery-spec', 64 * 1024)
        assert handle.waitForExit() == 0
        Drain.joinAll(handle.postExitBound(), drain)
        drain.captured()
    }
}
