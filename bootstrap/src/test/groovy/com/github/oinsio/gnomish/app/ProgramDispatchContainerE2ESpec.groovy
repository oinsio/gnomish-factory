package com.github.oinsio.gnomish.app

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.e2e.gitea.GiteaContainerFixture
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.GuardImageAvailability
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.AutoCleanup
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The identity of design D8 of define-executor-contract in container mode (FR9, FR10; task 7.3): the
 * container {@code run} path, assembled through the composition root's own wiring, runs a mixed
 * pipeline in one box — a {@code program} stage naming {@code acme}, then an {@code agent-cli} stage —
 * and each stage reaches its own adapter through the run's one stage executor, both opening their
 * rounds through the sandboxed round source. Read from the effect only each adapter produces: the
 * round record each stage's round commit carries — the model and tool the program's result file
 * names, the fake agent's model and its {@code Write} call — and the program's round harvested from
 * the box like the agent's (its snapshot commit).
 *
 * <p>The declared program is the box's own shell, answering both operations: the stand-in library
 * is a host path, which a box cannot run (testing.md, the container exemption).
 *
 * <p>Docker- and guard-image-gated: skips cleanly with no daemon or no pullable mitmproxy image.
 */
@Timeout(value = 420, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class ProgramDispatchContainerE2ESpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    private static final ObjectMapper JSON = new ObjectMapper()

    private static final String DESCRIBE = '{"protocolVersion":1,"version":"1","roles":["transform"],"capabilities":[],' +
    '"settingsSchema":{},"credentials":[],"egress":[],"policyDisablingFlags":[],"enforcementLevel":"PROMPT_ONLY",' +
    '"reportsUsage":true}'

    private static final String RESULT = '{"status":"completed","usage":{"provenance":"REPORTED","wallMillis":700,' +
    '"models":{"acme-box-model":{"input":7,"output":3,"cacheCreation":0,"cacheRead":0}},' +
    '"tools":[{"name":"box-lint","calls":1,"totalMillis":5}]}}'

    /** describe writes its answer; start writes a completed result. The operation word arrives as $1. */
    private static final String PROGRAM = "case \"\$1\" in describe) printf '%s' '${DESCRIBE}' > \"\$GNOMISH_RESULT_FILE\";; " +
    "start) printf '%s' '${RESULT}' > \"\$GNOMISH_RESULT_FILE\";; esac"

    @Shared
    @AutoCleanup('stop')
    GiteaContainerFixture gitea = new GiteaContainerFixture()

    @TempDir
    Path tempDir

    Path cloneDir
    String taskId = 'CTN-DISPATCH-1'

    def setupSpec() {
        gitea.start()
    }

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'dispatch-project')
        Files.createDirectories(cloneDir.resolve('.gnomish'))
        Files.writeString(cloneDir.resolve('.gnomish/instructions.md'), 'build it\n')
        commitAll(cloneDir)
        def originUrl = gitea.createRepository("program-dispatch-${System.nanoTime()}")
        addRemote(cloneDir, 'origin', originUrl)
        gitExitCode(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
    }

    def cleanup() {
        ContainerE2eDocker.removeTaskObjects(taskId)
    }

    private static StageDefinition stage(String name, ExecutorType type, String model, String executor) {
        new StageDefinition(name, 'purpose', [], [], new StageDefinition.Executor(type, model, [:], Sandbox.none(), executor),
        'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    // FR9, FR10, D8: one stage executor, two adapters, each reached for its own stage, in one box
    def "FR9, FR10: in container mode a mixed pipeline runs its program stage through the program executor and its agent stage through the agent"() {
        given: 'a container-mode runner over the fake-agent sandbox image, and acme the box shell'
        def image = FakeAgentSandboxImage.ensureBuilt('plain-round')
        def sandbox = new SandboxProperties(image, null, null, null, [], [], false, null, null, null, null)
        def factoryProps = testProperties(agentCliBinary: FakeAgentSandboxImage.BINARY)
        def git = TaskGitFixture.real()
        def runner = new ContainerGitModeRunner(
                newAssembly(factoryProps), git, ContainerSupportFixture.real(git.epochs(), sandbox, factoryProps),
                LiveConsoleIO.onStdout())
        def stages = [
            stage('build', ExecutorType.PROGRAM, '', 'acme'),
            stage('review', ExecutorType.AGENT_CLI, 'model-x', null)
        ]
        def law = new PipelineDefinition('1', new AutonomyLimits(3), stages, null, [
            new ExecutorDeclaration('acme', ExecutorDeclaration.Type.PROGRAM, ['sh', '-c', PROGRAM, 'acme'], [:], [], [])
        ])
        def segments = [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), stages)
        ]

        when:
        runner.run(new RunOrder(cloneDir, null, law, false), segments,
                new TaskContext(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body'), List.<Decision> of()),
                TaskState.atStageStart('build'))

        then: "the program stage's round is the program's, harvested from the box like an agent round"
        gitOutput(cloneDir, 'log', "gnomish/${taskId}", '--format=%H', '--grep', '^gnomish: snapshot build#0 [0-9a-f]*$')
        with(roundOf('build')) {
            get('executor').asText() == 'acme'
            get('byModel').fieldNames().toList() == ['acme-box-model']
            get('byTool')*.get('name')*.asText() == ['box-lint']
        }

        and: "the agent stage's round is the agent's: the fake agent's model and its Write call"
        with(roundOf('review')) {
            get('executor').asText() == 'agent-cli'
            get('byModel').fieldNames().toList() == ['claude-fake-main-1']
            get('byTool')*.get('name')*.asText() == ['Write']
        }

        and: 'the run completed: the tip is cleaned'
        !gitOutput(cloneDir, 'ls-tree', '-r', '--name-only', "gnomish/${taskId}").contains('.gnomish-task/')
    }

    /** The one participant entry of {@code stage}'s first round, read from that round's own commit. */
    private def roundOf(String stage) {
        def commit = gitOutput(cloneDir, 'log', '--format=%H', '--grep', "^gnomish: round ${stage}#0\$", "gnomish/${taskId}").trim()
        assert commit
        def attempts = JSON.readTree(gitOutput(cloneDir, 'show', "${commit}:.gnomish-task/state.json")).get('attempts')
        def record = attempts.find { it.get('stage').asText() == stage }
        assert record != null && record.get('usage').size() == 1
        record.get('usage').get(0)
    }
}
