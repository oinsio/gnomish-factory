package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.SandboxProperties
import com.github.oinsio.gnomish.adapter.environment.AdapterBinding
import com.github.oinsio.gnomish.adapter.environment.ExecCommand
import com.github.oinsio.gnomish.adapter.environment.GuardImageAvailability
import com.github.oinsio.gnomish.adapter.environment.Segment
import com.github.oinsio.gnomish.adapter.git.AttemptCommitRef
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.EnvironmentRoundSnapshot
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
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
 * M4, FR21, FR23 of add-sandbox-core (task 9.4): a sandboxed task interrupted
 * mid-flight is resumed by a second factory instance from the branch alone —
 * the kept box is reattached, uncommitted leftovers are salvaged in-box, a
 * pending decision request is recovered from the branch, and a task killed
 * between the snapshot and state commits re-verifies its attempt commit
 * without an agent re-run and without burning the attempt.
 *
 * <p>"Second instance" = a freshly constructed {@link ContainerResumeRunner}
 * with fresh collaborators over the same clone: instance state lives only in
 * the tracker and the task branch by design, so new objects are the honest
 * equivalent of a new process (the cross-clone leg is host-mode
 * {@code GiteaCrossInstanceResumeE2ESpec}'s concern and is mode-independent).
 *
 * <p>Implements M4, FR6, FR21, FR23 of add-sandbox-core.
 */
@Timeout(value = 420, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class ContainerModeResumeE2ESpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

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
        cloneDir = initWorkingRepo(tempDir, 'resume-project')
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
            new Segment(AdapterBinding.CONTAINER, [stage()])
        ]
    }

    private static SandboxProperties sandbox(String scenario) {
        new SandboxProperties(FakeAgentSandboxImage.ensureBuilt(scenario), null, null, null, [], [], false)
    }

    // M4 + FR23: instance one dies mid-escalation (EOF console) — the pending decision request
    // rides the snapshot commit to the remote; instance two resumes from the branch alone,
    // reattaches the kept (stopped) box, salvages a leftover planted after the death, and
    // completes.
    def "a second instance resumes a killed task: kept box reattached, leftovers salvaged, decision on the branch"() {
        given:
        taskId = "CTN-RES-${System.nanoTime() % 100000}"
        def sandboxProps = sandbox('decision-then-plain')
        def factoryProps = testProperties(agentCliBinary: FakeAgentSandboxImage.BINARY)
        def context = new TaskContext(taskId, 'title', 'body', List.<Decision> of())

        when: 'instance one runs with an immediately-EOF console and dies at the escalation dialog'
        def instanceOne = new ContainerGitModeRunner(
                newAssembly(new ByteArrayInputStream(new byte[0]), System.out, factoryProps), sandboxProps, factoryProps)
        instanceOne.run(cloneDir, null, pipeline(), segments(), context,
                TaskState.atStageStart('work'), RunArguments.InteractiveMode.NONE)

        then: 'the dialog EOF killed the run'
        thrown(EscalationEofException)

        and: 'the kept box is stopped, volume and network retained (keep semantics)'
        def boxName = "gnomish-box-${taskId}"
        ContainerE2eDocker.containerExists(boxName)
        !ContainerE2eDocker.containerRunning(boxName)

        and: 'the pending decision request rode the snapshot commit into the branch (FR23)'
        def branch = "gnomish/${taskId}"
        gitRunner.run(cloneDir, 'ls-tree', '-r', '--name-only', branch).stdout()
                .contains(".gnomish-task/decisions/work-a0.json")

        when: 'a leftover is planted in the kept box (the uncommitted tail of a dead round)'
        ContainerE2eDocker.start(boxName)
        ContainerE2eDocker.execInBox(boxName, 'cd /gnomish/work && echo leftover > leftover.txt')

        and: 'a second instance resumes from the branch alone'
        new ContainerResumeRunner(newAssembly(factoryProps), sandboxProps, factoryProps, 'taskId')
                .run(cloneDir, taskId, pipeline(), segments(), RunArguments.InteractiveMode.NONE, false)

        then: 'the leftover was salvaged in-box and harvested (FR6)'
        def salvageSha = gitRunner.run(cloneDir, 'log', branch, '--format=%H', '--grep',
                '^gnomish: salvage$').stdout().trim()
        salvageSha
        gitRunner.run(cloneDir, 'ls-tree', '-r', '--name-only', salvageSha).stdout().contains('leftover.txt')

        and: 'the task completed: cleaned tip with the work present, environment disposed'
        def tipTree = gitRunner.run(cloneDir, 'ls-tree', '-r', '--name-only', branch).stdout()
        tipTree.contains('output.txt')
        !tipTree.contains('.gnomish-task/')
        ContainerE2eDocker.taskObjects(taskId).isEmpty()

        and: 'the completed branch reached the real remote'
        def freshClone = tempDir.resolve('fresh-verify-clone')
        gitRunner.run(tempDir, 'clone', gitea.authenticatedCloneUrl(), freshClone.toString())
        gitRunner.run(freshClone, 'fetch', 'origin', "${branch}:refs/remotes/origin/${branch}")
                .exitCode() == 0
    }

    // FR21/D15: killed between the snapshot and state commits — resume classifies the tip as an
    // interrupted verification, re-verifies exactly that attempt commit with NO agent re-run
    // (the baked scenario would poison the run if the agent executed), and burns no attempt.
    def "a task killed between snapshot and state commit re-verifies without an agent re-run"() {
        given: 'a created task whose round completed in-box up to the snapshot, but never recorded state'
        taskId = "CTN-SNAP-${System.nanoTime() % 100000}"
        // garbage-output: if the resumed run re-executed the agent, the round would fail as an
        // infrastructure failure and the task could not complete — completion proves the skip.
        def sandboxProps = sandbox('garbage-output')
        def factoryProps = testProperties(agentCliBinary: FakeAgentSandboxImage.BINARY)
        def support = ContainerRunSupport.create(cloneDir, taskId, segments(), sandboxProps,
                factoryProps, [])
        support.taskRepository().createTask(new TaskContext(taskId, 'title', 'body', List.<Decision> of()), 'HEAD')

        and: 'the interrupted round: work written and snapshot-committed in-box, then the factory died'
        def environment = support.lease().environmentFor('work')
        def handle = environment.exec(new ExecCommand([
            'sh',
            '-c',
            'echo done > output.txt'
        ], [:], null, true))
        handle.output().readAllBytes()
        assert handle.waitForExit() == 0
        new EnvironmentRoundSnapshot(environment, gitRunner, cloneDir, taskId, new AttemptCommitRef())
                .snapshot(taskId, 'work', 0)
        support.keepStopped()

        when: 'a second instance resumes'
        new ContainerResumeRunner(newAssembly(factoryProps), sandboxProps, factoryProps, 'taskId')
                .run(cloneDir, taskId, pipeline(), segments(), RunArguments.InteractiveMode.NONE, false)

        then: 'the task completed — verification judged the harvested attempt commit, no agent ran'
        def branch = "gnomish/${taskId}"
        def tipTree = gitRunner.run(cloneDir, 'ls-tree', '-r', '--name-only', branch).stdout()
        tipTree.contains('output.txt')
        !tipTree.contains('.gnomish-task/')

        and: 'exactly one round was recorded — the interrupted one, no attempt burned (FR21)'
        def stateShas = gitRunner.run(cloneDir, 'log', branch, '--format=%H', '--grep',
                '^gnomish: round work#0$').stdout().readLines().findAll { !it.isBlank() }
        stateShas.size() == 1
        gitRunner.run(cloneDir, 'log', branch, '--grep', '^gnomish: round work#1$', '--format=%H')
                .stdout().trim().isEmpty()
    }
}
