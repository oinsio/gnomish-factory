package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.check.github.GithubCheckClientFactory
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.adapter.sandbox.DiscoveredBindings
import com.github.oinsio.gnomish.app.console.SystemConsoleIO
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.e2e.gitea.GiteaContainerFixture
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.GuardImageAvailability
import com.github.oinsio.gnomish.sandbox.environment.OwnershipMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.TimeUnit
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.AutoCleanup
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * Closes two real-Docker mutation-coverage gaps left by earlier tasks of add-serve-sandbox-
 * lifecycle, both reachable only through paths every other spec deliberately stops short of:
 *
 * <ul>
 *   <li>{@link ManualRunRunner}'s own container-support lambda (task 5.1's {@code mode=manual}
 *       labelling closure) is never actually INVOKED by {@code ManualRunContainerDispatchSpec} —
 *       that spec stops each dispatch at its own early usage refusal, before an environment is
 *       ever materialized. This spec drives one fresh container-mode {@code gnomish run} all the
 *       way to completion through the real {@link ManualRunRunner}, so the lambda actually runs.
 *   <li>{@link ContainerRunSupport#revocationSalvageAndPush}, the tracker-take revocation salvage
 *       protocol (FR15 of add-tracker-port): {@code TakeContainerEngineExecutionSpec} proves the
 *       CALLER invokes this port method over a stub, but nothing exercises the concrete bootstrap
 *       adapter's own implementation (salvage, then a real push).
 * </ul>
 *
 * <p>Docker- and guard-image-gated: skips cleanly with no daemon or no pullable mitmproxy image.
 */
@Timeout(value = 420, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class ContainerLifecycleCoverageGapsE2ESpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @Shared
    @AutoCleanup('stop')
    GiteaContainerFixture gitea = new GiteaContainerFixture()

    @TempDir
    Path tempDir

    String taskId

    def setupSpec() {
        gitea.start()
    }

    def cleanup() {
        if (taskId != null) {
            ContainerE2eDocker.removeTaskObjects(taskId)
        }
    }

    private static List<Segment> segments(def stage) {
        [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [stage])
        ]
    }

    // ManualRunRunner's real container-support lambda: a fresh gnomish run under container
    // bindings, driven to completion (task, box, round, harvest, dispose) through the actual
    // composition root — the one path that materializes an environment via that lambda.
    def "a fresh container-mode gnomish run completes end to end through ManualRunRunner's own wiring"() {
        given: 'a project dir with a one-stage container-bound pipeline'
        def projectRoot = initWorkingRepo(tempDir, 'manual-run-project')
        Files.createDirectories(projectRoot.resolve('.gnomish/stages/build'))
        Files.createDirectories(projectRoot.resolve('stages/build'))
        Files.writeString(projectRoot.resolve('.gnomish/config.yaml'), 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\n')
        Files.writeString(projectRoot.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(projectRoot.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectRoot.resolve('stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectRoot.resolve('.gnomish/stages/build/stage.yaml'), '''\
purpose: build it
executor:
  type: agent-cli
  model: model-x
instructions: stages/build/instructions.md
verify:
  - type: builtin
    name: files_exist
    params:
      files: [output.txt]
advancement: auto
''')
        commitAll(projectRoot)

        and: 'the real ManualRunRunner wiring, over a real image and the fake-agent binary'
        def image = FakeAgentSandboxImage.ensureBuilt('plain-round')
        def factoryProps = testProperties(agentCliBinary: FakeAgentSandboxImage.BINARY)
        def runner = new ManualRunRunner(
                new RunArgumentsParser(),
                new PipelineStartup(TrackerValidatorStub.plainSource()),
                new AdHocTaskSynthesizer(Clock.systemUTC(), new Random()),
                new SystemConsoleIO(System.in, System.out),
                new FilesExistCheckRunner(),
                new ShellCommandCheckRunner(),
                [(GithubCheckClientFactory.PROVIDER): new GithubCheckClientFactory()],
                new InMemoryAttemptPersistence(),
                new SystemClock(),
                new ThreadSleeper(),
                factoryProps,
                new SandboxProperties(image, null, null, null, [], [], false, null, null, null, null),
                new BindingProperties(null, [:]),
                DiscoveredBindings.real(),
                TaskGitFixture.real(),
                tempDir.resolve('worktrees'),
                tempDir.resolve('home'),
                new StatusCommand(TaskGitFixture.real(), tempDir.resolve('worktrees')),
                new UsageCommand(TaskGitFixture.real()),
                new BoardCommand(Clock.systemUTC(), factoryProps, [:], MapSecretsProvider.NONE, TrackerValidatorStub.plainSource()),
                new DashboardCommand(Clock.systemUTC(), new ThreadSleeper(), tempDir.resolve('home'), factoryProps, [:],
                MapSecretsProvider.NONE, TrackerValidatorStub.plainSource()),
                Clock.systemUTC(),
                [:],
                MapSecretsProvider.NONE,
                TrackerValidatorStub.plainSource(),
                new ServeProperties(0, null, null, null, null, null, null))

        when:
        taskId = 'ct-manual-1'
        runner.run(new DefaultApplicationArguments(
                        "--dir=${projectRoot}".toString(), '--task=do the thing', "--task-id=${taskId}"))

        then: 'the run completed: the branch carries the work, and the environment is disposed'
        def branch = "gnomish/${taskId}"
        def tipTree = gitOutput(projectRoot, 'ls-tree', '-r', '--name-only', branch)
        tipTree.contains('output.txt')
        !tipTree.contains('.gnomish-task/')
        ContainerE2eDocker.taskObjects(taskId).isEmpty()
    }

    // FR15 of add-tracker-port: revocationSalvageAndPush over the real bootstrap adapter — salvage
    // harvests the in-box leftover, and the push reaches the real remote.
    def "ContainerRunSupport.revocationSalvageAndPush salvages the leftover and pushes to the real remote"() {
        given: 'a project clone with a real remote, and a box carrying an un-harvested leftover'
        def cloneDir = initWorkingRepo(tempDir, 'revocation-project')
        Files.writeString(cloneDir.resolve('instructions.md'), 'build it\n')
        commitAll(cloneDir, 'init')
        addRemote(cloneDir, 'origin', gitea.authenticatedCloneUrl())
        gitOutput(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')

        taskId = "CTN-REVOKE-${System.nanoTime() % 100000}"
        def image = FakeAgentSandboxImage.ensureBuilt('plain-round')
        def sandboxProps = new SandboxProperties(image, null, null, null, [], [], false, null, null, null, null)
        def stage = new StageDefinition(
                'work', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [], new AutonomyLimits(3),
                AdvancementMode.AUTO)
        def support = ContainerRunSupport.create(cloneDir, taskId, segments(stage), sandboxProps,
                List.<String> of(), [], OwnershipMode.TRACKED)
        support.taskRepository().createTask(new TaskContext(taskId, 'title', 'body', List.<Decision> of()), 'HEAD')
        def environment = support.lease().environmentFor('work')
        def handle = environment.exec(new ExecCommand(
                        [
                            'sh',
                            '-c',
                            'echo revoked-leftover > revoked.txt'
                        ], [:], null, true))
        handle.output().readAllBytes()
        assert handle.waitForExit() == 0

        when:
        support.revocationSalvageAndPush(taskId)

        then: 'the leftover was salvaged into the branch'
        def branch = "gnomish/${taskId}"
        def salvageSha = gitOutput(cloneDir, 'log', branch, '--format=%H', '--grep', '^gnomish: salvage$')
        salvageSha
        gitOutput(cloneDir, 'ls-tree', '-r', '--name-only', salvageSha).contains('revoked.txt')

        and: 'the salvage commit reached the real remote'
        def freshClone = tempDir.resolve('fresh-verify-clone')
        gitOutput(tempDir, 'clone', gitea.authenticatedCloneUrl(), freshClone.toString()) != null
        gitOutput(freshClone, 'fetch', 'origin', "${branch}:refs/remotes/origin/${branch}") != null
        gitOutput(freshClone, 'cat-file', '-e', salvageSha) != null
    }
}
