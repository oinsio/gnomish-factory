package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
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
 * FR5, NFR-S3 of fix-operator-blockers (design D3), container mode: the agent CLI's own
 * credentials reach the agent round in the box through the AI seam, with no operator passthrough,
 * and stay out of the command checks that follow it.
 *
 * <p>The seam reads the factory process's real environment, which a spec cannot inject into. So
 * {@code :bootstrap}'s {@code test} task sets placeholder values for both names
 * ({@code verification.gradle}), and this spec first asserts they are in effect — a run outside
 * Gradle fails on that precondition instead of passing over an empty environment. The round's
 * in-box values are read back from the snapshot commit ({@link
 * FakeAgentSandboxImage#ensureBuiltCapturingCredentials}); each command check passes only when
 * both names are unset in its own box, so the stage completing is the proof they stayed out.
 *
 * <p>Docker- and guard-image-gated: skips cleanly with no daemon or no pullable mitmproxy image.
 */
@Timeout(value = 420, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class ContainerModeAgentCredentialE2ESpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    private static final List<String> CREDENTIAL_NAMES = [
        'CLAUDE_CODE_OAUTH_TOKEN',
        'ANTHROPIC_API_KEY'
    ]

    /** Exit 0 only when neither credential is set in the check's own environment. */
    private static final String CREDENTIALS_ABSENT =
    'test -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" && test -z "${ANTHROPIC_API_KEY:-}"'

    @Shared
    @AutoCleanup('stop')
    GiteaContainerFixture gitea = new GiteaContainerFixture()

    @TempDir
    Path tempDir

    Path cloneDir
    String taskId = 'CTN-CRED-1'

    def setupSpec() {
        gitea.start()
    }

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'container-project')
        Files.createDirectories(cloneDir.resolve('.gnomish'))
        Files.writeString(cloneDir.resolve('.gnomish/instructions.md'), 'build it\n')
        commitAll(cloneDir)
        addRemote(cloneDir, 'origin', gitea.createRepository("container-credential-${System.nanoTime()}"))
        gitExitCode(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
    }

    def cleanup() {
        ContainerE2eDocker.removeTaskObjects(taskId)
    }

    /** One agent round, then a command check in the round's own box and one in a fresh box. */
    private static StageDefinition stage() {
        new StageDefinition(
                'work', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md',
                (List<VerifyCheck>) [
                    new VerifyCheck.Command(CREDENTIALS_ABSENT, VerifyCheck.VerifyIn.SAME_BOX),
                    new VerifyCheck.Command(CREDENTIALS_ABSENT, VerifyCheck.VerifyIn.FRESH_BOX),
                ],
                new AutonomyLimits(1), AdvancementMode.AUTO)
    }

    // FR5, NFR-S3: spec scenarios "Subscription token reaches the box…" and "Agent credential
    //     stays out of command checks", for both names at once.
    def "FR5, NFR-S3: agent credentials reach the round in the box without passthrough and stay out of command checks"() {
        given: 'placeholder credentials in the factory environment (set by the test task)'
        def factoryValues = CREDENTIAL_NAMES.collectEntries {
            [(it): System.getenv(it)]
        }
        assert factoryValues.values().every {
            it
        }: "run through Gradle: :bootstrap's test task sets ${CREDENTIAL_NAMES} (verification.gradle)"

        and: 'a container-mode runner with an empty env-passthrough over the credential-capturing agent'
        def image = FakeAgentSandboxImage.ensureBuiltCapturingCredentials()
        def sandbox = new SandboxProperties(image, null, null, null, [], [], false, null, null, null, null)
        def factoryProps = testProperties(agentCliBinary: FakeAgentSandboxImage.CREDENTIAL_CAPTURING_BINARY)
        def git = TaskGitFixture.real()
        def runner = new ContainerGitModeRunner(
                newAssembly(factoryProps), git, sandbox, factoryProps, ContainerSupportFixture.real(git.epochs()),
                LiveConsoleIO.onStdout())
        def segments = [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [stage()])
        ]

        when:
        runner.run(new RunOrder(cloneDir, null, new PipelineDefinition('1', new AutonomyLimits(1), [stage()]),
        false),
        segments, new TaskContext(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body'),
        List.<Decision> of()), TaskState.atStageStart('work'))

        then: 'the agent round held both credentials with their current factory values'
        def branch = "gnomish/${taskId}"
        def snapshotSha = gitOutput(cloneDir, 'log', branch, '--format=%H', '--grep',
                '^gnomish: snapshot work#0$')
        def captured = gitOutput(cloneDir, 'show', "${snapshotSha}:${FakeAgentSandboxImage.CREDENTIAL_CAPTURE}")
        captured.readLines().collectEntries { line ->
            line.split('=', 2).with {
                [(it[0]): it[1]]
            }
        } == factoryValues

        and: 'both command checks passed, which each allows only with neither credential in its box'
        def tipTree = gitOutput(cloneDir, 'ls-tree', '-r', '--name-only', branch)
        !tipTree.contains('.gnomish-task/')
    }
}
