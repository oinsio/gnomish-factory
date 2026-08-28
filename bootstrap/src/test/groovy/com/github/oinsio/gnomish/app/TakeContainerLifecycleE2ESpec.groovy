package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.adapter.tracker.FixedTrackerAdapterFactory
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.e2e.gitea.GiteaContainerFixture
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.BindingTrustTable
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.ContainerBindingProvider
import com.github.oinsio.gnomish.sandbox.environment.DockerRuntimeProbe
import com.github.oinsio.gnomish.sandbox.environment.GuardImageAvailability
import com.github.oinsio.gnomish.sandbox.environment.OwnershipMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import spock.lang.AutoCleanup
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * Task 7.1 of add-serve-sandbox-lifecycle (FR1, M1-adjacent): a container-mode {@code take}
 * completes a real tracker task end to end — claim, rounds in the box, harvest, push to a real
 * remote, tracker outcome, disposed environment — driven through the real {@link TakeCommand} CLI
 * entry point exactly as {@code TakeLifecycleReadyToDeliveredSpecBase} proves for host mode, with
 * container mode's own collaborators (real Docker, real {@link GiteaContainerFixture} remote)
 * swapped in for the host worktree/mock-free assertions that spec already covers.
 *
 * <p>Docker- and guard-image-gated: skips cleanly with no daemon or no pullable mitmproxy image.
 *
 * <p>Implements FR1 of add-serve-sandbox-lifecycle; M1-adjacent, M3, UX4 of add-tracker-port.
 */
@Timeout(value = 420, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class TakeContainerLifecycleE2ESpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture, ApplicationArgumentsFixture {

    private static final TaskRef REF = new TaskRef('CTN-TAKE-1')

    @Shared
    @AutoCleanup('stop')
    GiteaContainerFixture gitea = new GiteaContainerFixture()

    @TempDir
    Path tempDir

    Path projectDir
    Path worktreesRoot

    // Wired per feature, so it gets its own repository — see GiteaContainerFixture's sharing rule.
    String originUrl

    def setupSpec() {
        gitea.start()
    }

    def setup() {
        projectDir = initWorkingRepo(tempDir, 'container-take-project')
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.createDirectories(projectDir.resolve('stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/stage.yaml'), '''\
purpose: build it
executor:
  type: agent-cli
  model: claude-fake-main-1
instructions: stages/build/instructions.md
advancement: auto
''')
        Files.writeString(
                projectDir.resolve('.gnomish/config.yaml'),
                '''\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        commitAll(projectDir)
        originUrl = gitea.createRepository("container-take-${System.nanoTime()}")
        addRemote(projectDir, 'origin', originUrl)
        gitOutput(projectDir, 'push', 'origin', 'HEAD:refs/heads/main')
        worktreesRoot = tempDir.resolve('worktrees')
    }

    def cleanup() {
        ContainerE2eDocker.removeTaskObjects(REF.id())
    }

    private static ContainerTakeSupport containerTakeSupport(FactoryProperties factoryProperties) {
        def image = FakeAgentSandboxImage.ensureBuilt('plain-round')
        def sandbox = new SandboxProperties(image, null, null, null, [], [], false, null, null, null, null)
        def registry = AdapterBindingRegistry.ratified([
            new ContainerBindingProvider()
        ], BindingTrustTable.firstParty())
        def bindings = new BindingProperties(BindingNames.CONTAINER, [:])
        def containerSupport = { Path clone, String id, List<Segment> segments, SandboxProperties sandboxProps, FactoryProperties factoryProps, PipelineDefinition definition, List<String> creds ->
            ContainerRunSupport.create(clone, id, segments, sandboxProps, factoryProps, [], creds, OwnershipMode.TRACKED, ClaimEpochSource.NONE)
        }
        new ContainerTakeSupport(
                factoryProperties, bindings, sandbox, registry, DockerRuntimeProbe.&dockerAvailable, containerSupport)
    }

    private TakeCommand newCommand(FactoryProperties factoryProperties, TrackerAdapterFactory trackerFactory) {
        TakeCommandFactory.of(
                newAssembly(factoryProperties),
                TaskGitFixture.real(),
                worktreesRoot,
                'taskId',
                factoryProperties,
                Clock.fixed(Instant.parse('2026-01-01T00:00:00Z'), ZoneOffset.UTC),
                [github: trackerFactory],
                MapSecretsProvider.NONE,
                TrackerValidatorStub.acceptingGithubSource(),
                SandboxLifecyclePass.NONE,
                containerTakeSupport(factoryProperties))
    }

    // M1-adjacent, FR1: ready -> claim -> a real fake-agent round inside a real box -> harvest ->
    // factory-side push to a real remote -> Finished on the tracker -> disposed environment. The
    // same lifecycle TakeLifecycleReadyToDeliveredSpecBase proves for host mode, now over the
    // container assembly.
    def "a container-mode take completes a real tracker task end to end and disposes its environment"() {
        given: 'a Ready task seeded in a real (in-memory) tracker, and a fake-agent-backed stage'
        def tracker = new InMemoryTracker()
        new InMemoryTrackerHarness(tracker).seed(
                REF, new TaskSnapshot(REF.id(), 'Add widgets', 'please add widgets'),
                new TrackerTaskState.Ready(), AbortFacts.none())
        def trackerFactory = new FixedTrackerAdapterFactory({ tracker })
        FakeAgentSandboxImage.ensureBuilt('plain-round')
        def factoryProperties = testProperties(agentCliBinary: FakeAgentSandboxImage.BINARY)
        def command = newCommand(factoryProperties, trackerFactory)

        when: 'take is run against the seeded ref in explicit mode'
        command.run(args('take', REF.id(), "--dir=$projectDir"))

        then: 'the run reaches the Delivered exit code (0)'
        def ex = thrown(TakeExitCodeException)
        ex.exitCode() == 0

        and: 'the tracker itself ends Finished'
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.Finished

        and: 'the branch really ran in the box and reached the real remote via the factory-side push'
        def branch = "gnomish/${REF.id()}"
        def freshClone = tempDir.resolve('fresh-verify-clone')
        gitExitCode(tempDir, 'clone', originUrl, freshClone.toString()) == 0
        gitExitCode(freshClone, 'fetch', 'origin', "${branch}:refs/remotes/origin/${branch}") == 0
        def tipTree = gitOutput(freshClone, 'ls-tree', '-r', '--name-only', "origin/${branch}")
        tipTree.contains('output.txt')

        and: 'the task environment is disposed: no container, volume, or network object remains'
        ContainerE2eDocker.taskObjects(REF.id()).isEmpty()
    }
}
