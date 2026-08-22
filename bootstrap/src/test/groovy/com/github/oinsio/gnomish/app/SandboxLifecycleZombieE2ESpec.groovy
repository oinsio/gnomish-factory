package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.app.lease.LivenessVerdict
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
import com.github.oinsio.gnomish.sandbox.environment.OwnershipMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit
import spock.lang.AutoCleanup
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * Task 7.2 of add-serve-sandbox-lifecycle (M1, NFR-R4, NFR-C1): a container-mode holder killed
 * mid-task leaves a zombie — a running, unowned box. The daemon's sweep tick stops it (never
 * disposes it: volume and network survive), and a later resume salvages the un-harvested work
 * from the surviving volume exactly as after an ordinary keep.
 *
 * <p>The claim-staleness half of "sibling seizes after TTL" ({@code StalenessMemory}/{@code
 * LivenessOracle} judging a claim's own version stale over two observations spanning the TTL) is
 * already proven by that component's own specs (task 2.x) and {@code
 * TakeDeathAndRecoverySpecBase} at the host level; duplicating that dance here over real Docker
 * would only slow this spec down without adding coverage. This spec starts from the oracle's
 * OUTPUT — a {@link LivenessVerdict.Live} that omits the zombie's task — and proves the Docker
 * side of the mechanism the design promises from that verdict onward: stop, not dispose; resume
 * salvages.
 *
 * <p>The "died, box still running" setup mirrors {@code ContainerModeResumeE2ESpec}'s own: instance
 * one dies at an EOF escalation dialog (a legitimate kept-stopped box with real branch state), then
 * the box is restarted and a leftover planted — modelling a hang rather than a clean death, exactly
 * {@code ContainerModeResumeE2ESpec}'s "leftover planted in the kept box" step, repurposed here as
 * the zombie: still running when the sweep finds it.
 *
 * <p>Docker- and guard-image-gated: skips cleanly with no daemon or no pullable mitmproxy image.
 *
 * <p>Implements M1, NFR-R4, NFR-C1 of add-serve-sandbox-lifecycle.
 */
@Timeout(value = 420, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class SandboxLifecycleZombieE2ESpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @Shared
    @AutoCleanup('stop')
    GiteaContainerFixture gitea = new GiteaContainerFixture()

    @TempDir
    Path tempDir

    Path cloneDir
    def gitRunner = new GitProcessRunner()
    String taskId

    def setupSpec() {
        gitea.start()
    }

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'zombie-project')
        Files.writeString(cloneDir.resolve('instructions.md'), 'build it\n')
        gitRunner.run(cloneDir, 'add', 'instructions.md')
        gitRunner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        gitRunner.run(cloneDir, 'remote', 'add', 'origin', gitea.authenticatedCloneUrl())
        gitRunner.run(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
    }

    def cleanup() {
        if (taskId != null) {
            ContainerE2eDocker.removeTaskObjects(taskId)
        }
    }

    private static StageDefinition stage() {
        new StageDefinition(
                'work', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md',
                [
                    new VerifyCheck.Builtin('files_exist', [files: ['output.txt']])
                ],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage()])
    }

    private static List<Segment> segments() {
        [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [stage()])
        ]
    }

    // A minimum age of 1ms: the zombie box in this spec is only seconds old by the time the sweep
    // runs, and the real operator default (2m) exists to protect a slot still mid-launch — not
    // something this spec is proving (that is SandboxLifecycleLaunchRaceE2ESpec's job, task 7.3).
    private static SandboxProperties sandboxProperties(String scenario) {
        new SandboxProperties(FakeAgentSandboxImage.ensureBuilt(scenario), null, null, null, [], [], false, null,
        Duration.ofMillis(1), Duration.ofDays(7), Duration.ofHours(24))
    }

    // Unlike ContainerSupportFixture.real() (deliberately OwnershipMode.MANUAL, for run-mode
    // specs), this spec's zombie must be `tracked` — the mode a liveness-verdict-driven sweep
    // actually governs — mirroring the composition root's own take/serve support lambda
    // (ManualRunRunner).
    private static ContainerSupportFactory trackedContainerSupport() {
        { cloneDir, id, segments, sandboxProps, factoryProps, definition, creds ->
            ContainerRunSupport.create(cloneDir, id, segments, sandboxProps, List.<String> of(), creds, OwnershipMode.TRACKED)
        } as ContainerSupportFactory
    }

    def "sweep stops a zombie box (never disposes it), and a later resume salvages the surviving volume"() {
        given: 'instance one dies at an escalation dialog (EOF console) — a real kept-stopped box'
        taskId = "CTN-ZMB-${System.nanoTime() % 100000}"
        def sandboxProps = sandboxProperties('decision-then-plain')
        def factoryProps = testProperties(agentCliBinary: FakeAgentSandboxImage.BINARY)
        def instanceOne = new ContainerGitModeRunner(
                newAssembly(new ByteArrayInputStream(new byte[0]), System.out, factoryProps), TaskGitFixture.real(),
                sandboxProps, factoryProps, trackedContainerSupport())

        when:
        instanceOne.run(cloneDir, null, pipeline(), segments(), new TaskContext(taskId, 'title', 'body', List.<Decision> of()),
                TaskState.atStageStart('work'), RunArguments.InteractiveMode.NONE)

        then:
        thrown(EscalationEofException)

        and: 'the kept box is stopped, volume and network retained (keep semantics)'
        def boxName = "gnomish-box-${taskId}"
        ContainerE2eDocker.containerExists(boxName)
        !ContainerE2eDocker.containerRunning(boxName)

        when: 'the box is restarted and a leftover planted — modelling a hang, not a clean death: still running when found'
        ContainerE2eDocker.start(boxName)
        ContainerE2eDocker.execInBox(boxName, 'cd /gnomish/work && echo leftover > leftover.txt')
        assert ContainerE2eDocker.containerRunning(boxName)
        // Docker's StartedAt has second precision; a short real sleep keeps the box's measured age
        // reliably past the 1ms minimum-age threshold above regardless of clock rounding.
        Thread.sleep(1500)

        and: 'the sweep evaluates the host with a liveness verdict that omits this task — the oracle already judged it unowned'
        def pass = SandboxLifecyclePassFactory.create(sandboxProps, Clock.systemUTC())
        def summary = pass.run(cloneDir, new LivenessVerdict.Live(Set.of()))

        then: 'the zombie box was stopped — not disposed — volume and network retained'
        summary.contains('stopped')
        ContainerE2eDocker.containerExists(boxName)
        !ContainerE2eDocker.containerRunning(boxName)
        ContainerE2eDocker.taskObjects(taskId).size() == 3 // box + volume + network, all still present

        when: 'a later resume runs from the branch alone'
        new ContainerResumeRunner(newAssembly(factoryProps), TaskGitFixture.real(), sandboxProps, factoryProps, 'taskId',
                trackedContainerSupport())
                .run(cloneDir, taskId, pipeline(), segments(), RunArguments.InteractiveMode.NONE, false)

        then: 'the leftover was salvaged in-box and harvested — the un-harvested tail is not lost'
        def branch = "gnomish/${taskId}"
        def salvageSha = gitRunner.run(cloneDir, 'log', branch, '--format=%H', '--grep', '^gnomish: salvage$')
                .stdout().trim()
        salvageSha
        gitRunner.run(cloneDir, 'ls-tree', '-r', '--name-only', salvageSha).stdout().contains('leftover.txt')

        and: 'the task completed and the environment is fully disposed'
        def tipTree = gitRunner.run(cloneDir, 'ls-tree', '-r', '--name-only', branch).stdout()
        tipTree.contains('output.txt')
        !tipTree.contains('.gnomish-task/')
        ContainerE2eDocker.taskObjects(taskId).isEmpty()
    }
}
