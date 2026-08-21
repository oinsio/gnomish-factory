package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.AppAssemblyFixture
import com.github.oinsio.gnomish.app.ContainerE2eDocker
import com.github.oinsio.gnomish.app.ContainerRunSupport
import com.github.oinsio.gnomish.app.ContainerTakeSupport
import com.github.oinsio.gnomish.app.FakeAgentSandboxImage
import com.github.oinsio.gnomish.app.TaskGitFixture
import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * Task 5.3 of add-serve-sandbox-lifecycle: proves the {@code factory-serve} scenario "Two slots
 * run containers concurrently" — two {@link TakeSlotRunner#run} calls, one shared runner over one
 * shared clone, dispatched from independent threads exactly as {@link FeedCycle#startSlot} spawns
 * one virtual thread per claimed slot, each completing its own task in its own box/volume/network
 * without touching the other's — isolation follows structurally from each container object's name
 * being derived from its own task key (design D3), proven here end to end over the real container
 * assembly rather than asserted from the label scheme alone.
 *
 * <p>Docker- and guard-image-gated: skips cleanly with no daemon or no pullable mitmproxy image,
 * mirroring {@code ContainerModePipelineE2ESpec}.
 *
 * <p>Implements FR1 of add-serve-sandbox-lifecycle; the {@code factory-serve} delta's "Container-
 * bound stages run in slots" requirement.
 */
@Timeout(value = 420, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class TakeSlotRunnerContainerConcurrencySpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    private static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')
    private static final int ABORT_THRESHOLD = 3
    private static final String MDC_KEY = 'taskId'
    private static final List<String> TASK_IDS = ['CTN-SLOT-1', 'CTN-SLOT-2']

    @TempDir
    Path tempDir

    Path cloneDir
    Path worktreesRoot
    def gitRunner = new GitProcessRunner()

    // The real adapter, not a Mock(): two slot threads drive this tracker concurrently (Spock's
    // mock controller is single-thread territory), and only a real tracker records the terminal
    // state that proves a slot ran to completion — TakeSlotRunner#run swallows every Throwable by
    // design, so a crashed slot is invisible from the calling thread.
    InMemoryTracker tracker = new InMemoryTracker()
    InMemoryTrackerHarness harness = new InMemoryTrackerHarness(tracker)

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'container-slots-project')
        Files.writeString(cloneDir.resolve('instructions.md'), 'build it\n')
        gitRunner.run(cloneDir, 'add', 'instructions.md')
        gitRunner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        worktreesRoot = tempDir.resolve('worktrees-root')
        // Both tasks already claimed by THIS instance — the state a slot is dispatched in.
        TASK_IDS.each {
            harness.seedWorkingWithClaim(tracker, new TaskRef(it), INSTANCE.value())
        }
    }

    def cleanup() {
        TASK_IDS.each { ContainerE2eDocker.removeTaskObjects(it) }
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

    private TakeSlotRunner newContainerSlotRunner(Tracker tracker) {
        def image = FakeAgentSandboxImage.ensureBuilt('plain-round')
        def sandbox = new SandboxProperties(image, null, null, null, [], [], false, null, null, null, null)
        def properties = testProperties(agentCliBinary: FakeAgentSandboxImage.BINARY)
        def registry = AdapterBindingRegistry.ratified([
            new ContainerBindingProvider()
        ], BindingTrustTable.firstParty())
        def bindings = new BindingProperties(BindingNames.CONTAINER, [:])
        // Mirrors the composition root's take/serve container support lambda (ManualRunRunner):
        // `tracked` ownership, since these are dispatched as already-claimed tracker tasks.
        def containerSupport = { Path clone, String id, List<Segment> segments, SandboxProperties sandboxProps, FactoryProperties factoryProps, PipelineDefinition definition, List<String> creds ->
            ContainerRunSupport.create(clone, id, segments, sandboxProps, [], creds, OwnershipMode.TRACKED)
        }
        def containerTakeSupport = new ContainerTakeSupport(
                properties, bindings, sandbox, registry, DockerRuntimeProbe.&dockerAvailable, containerSupport)
        def abortHandler = new AbortHandler(tracker, Clock.systemUTC())
        new TakeSlotRunner(
                newAssembly(properties), TaskGitFixture.real(), cloneDir, worktreesRoot, pipeline(), abortHandler,
                ABORT_THRESHOLD, MDC_KEY, [], ClaimBeat.NONE, new ClaimLossFlag(), tracker, INSTANCE,
                containerTakeSupport)
    }

    // Scenario (factory-serve): two slots hold container-bound tasks at once — each task runs in
    // its own box, volume, and network, and neither slot's lifecycle operations touch the other's
    // objects. One TakeSlotRunner (as `serve` builds once and reuses for the daemon's lifetime) is
    // dispatched from two independent threads exactly as FeedCycle spawns one virtual thread per
    // claimed slot.
    def "two slots run containers concurrently, each isolated by its own task key"() {
        given:
        def slotRunner = newContainerSlotRunner(tracker)
        def failures = new ConcurrentHashMap<String, Throwable>()

        when: 'both slots dispatch concurrently, one thread per claimed task'
        def threads = TASK_IDS.collect { id ->
            Thread.ofVirtual().name("test-slot-${id}").start {
                try {
                    slotRunner.run(new TaskRef(id))
                } catch (Throwable t) {
                    failures.put(id, t)
                }
            }
        }
        threads.each { it.join() }

        then: 'neither slot thread escaped with a throwable'
        failures.isEmpty()

        and: 'each task ran to a terminal Finished state in the tracker — not a swallowed crash'
        TASK_IDS.every { id ->
            tracker.fetchTask(new TaskRef(id)).state() instanceof TrackerTaskState.Finished
        }

        and: 'each task reached its own completed branch carrying its own stage output'
        TASK_IDS.every { id ->
            gitRunner.run(cloneDir, 'rev-parse', '--verify', "gnomish/${id}").exitCode() == 0
        }
        TASK_IDS.every { id ->
            gitRunner.run(cloneDir, 'ls-tree', '-r', '--name-only', "gnomish/${id}").stdout().contains('output.txt')
        }

        and: 'each task disposed exactly its own environment — nothing left over for either key'
        TASK_IDS.every { id -> ContainerE2eDocker.taskObjects(id).isEmpty() }
    }
}
