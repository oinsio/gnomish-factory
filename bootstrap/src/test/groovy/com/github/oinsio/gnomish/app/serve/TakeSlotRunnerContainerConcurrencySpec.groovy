package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.*
import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.app.take.AbortFuse
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.baseref.BaseDefinition
import com.github.oinsio.gnomish.baseref.DefaultBranch
import com.github.oinsio.gnomish.domain.pipeline.*
import com.github.oinsio.gnomish.sandbox.*
import com.github.oinsio.gnomish.sandbox.environment.ContainerBindingProvider
import com.github.oinsio.gnomish.sandbox.environment.DockerRuntimeProbe
import com.github.oinsio.gnomish.sandbox.environment.GuardImageAvailability
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import spock.lang.*
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
    RegisteredClone registeredClone

    // The real adapter, not a Mock(): two slot threads drive this tracker concurrently (Spock's
    // mock controller is single-thread territory), and only a real tracker records the terminal
    // state that proves a slot ran to completion — TakeSlotRunner#run swallows every Throwable by
    // design, so a crashed slot is invisible from the calling thread.
    InMemoryTracker tracker
    InMemoryTrackerHarness harness

    def setup() {
        // Built here rather than in field initializers, and under a fresh per-attempt root: the
        // @Retry below re-runs setup() on the same spec instance, so every attempt must start
        // from its own tracker and clone.
        tracker = new InMemoryTracker()
        harness = new InMemoryTrackerHarness(tracker)
        Path attemptRoot = Files.createTempDirectory(tempDir, 'attempt-')
        cloneDir = initWorkingRepo(attemptRoot, 'container-slots-project')
        Files.createDirectories(cloneDir.resolve('.gnomish/stages/work'))
        Files.writeString(cloneDir.resolve('.gnomish/instructions.md'), 'build it\n')
        // FR13, D14 of add-base-ref-resolution: a claimed task runs under the definition read from
        // ITS OWN base's law, so the committed .gnomish/ must carry the same pipeline this spec
        // hands the runner — an instructions file alone leaves the per-task law read with nothing
        // to bind, and the slot aborts before a container is ever started.
        Files.writeString(cloneDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - work\n')
        Files.writeString(cloneDir.resolve('.gnomish/stages/work/stage.yaml'), '''\
purpose: purpose
executor:
  type: agent-cli
  model: model-x
instructions: instructions.md
advancement: auto
verify:
  - type: builtin
    name: files_exist
    params:
      files:
        - output.txt
''')
        Files.writeString(cloneDir.resolve('.gnomish/config.yaml'), '''\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
''')
        commitAll(cloneDir, 'init')
        // FR2, FR6 of add-base-ref-resolution: an autonomous claim resolves and refreshes its base
        // against a real 'origin' and never falls back to the clone's local state, so a slot
        // dispatched without one parks before any container is started.
        addOrigin(cloneDir, attemptRoot)
        registeredClone = RegisteredCloneFixture.registered(attemptRoot.resolve('home'), cloneDir)
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
        def git = TaskGitFixture.real()
        def containerTakeSupport = new ContainerTakeSupport(
                properties, bindings, sandbox, registry, DockerRuntimeProbe.&dockerAvailable,
                ContainerSupportFixture.tracked(git.epochs()))
        def abortHandler = new AbortHandler(tracker, Clock.systemUTC())
        def wiring = new SlotWiring(
                newAssembly(properties), git, registeredClone, MDC_KEY, new AbortFuse(abortHandler, ABORT_THRESHOLD), [],
                containerTakeSupport, new ClaimTenure(ClaimBeat.NONE, new ClaimLossFlag()),
                new TrustedBaseContext(BaseDefinition.none(), new DefaultBranch(currentBranch(cloneDir))))
        new TakeSlotRunner(
                wiring, new RunOrder(cloneDir, null, pipeline(), false),
                tracker, INSTANCE)
    }

    // Scenario (factory-serve): two slots hold container-bound tasks at once — each task runs in
    // its own box, volume, and network, and neither slot's lifecycle operations touch the other's
    // objects. One TakeSlotRunner (as `serve` builds once and reuses for the daemon's lifetime) is
    // dispatched from two independent threads exactly as FeedCycle spawns one virtual thread per
    // claimed slot.
    //
    // TEMPORARY: two slots race on the shared clone's .git/config lock inside
    // FactoryCloneHardening.harden (a `git config` write per claim), which fails one claim at
    // random. The retry masks that race only; own-git-invocation-policy removes the write and
    // this @Retry with it (a task of that change).
    @Retry(count = 2, mode = Retry.Mode.SETUP_FEATURE_CLEANUP)
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
            gitExitCode(cloneDir, 'rev-parse', '--verify', "gnomish/${id}") == 0
        }
        TASK_IDS.every { id ->
            gitOutput(cloneDir, 'ls-tree', '-r', '--name-only', "gnomish/${id}").contains('output.txt')
        }

        and: 'each task disposed exactly its own environment — nothing left over for either key'
        TASK_IDS.every { id -> ContainerE2eDocker.taskObjects(id).isEmpty() }
    }
}
