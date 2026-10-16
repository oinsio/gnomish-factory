package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import java.util.concurrent.atomic.AtomicInteger
import spock.util.concurrent.PollingConditions

/**
 * The real entry points that reach the seven consumers of {@code DeliveredCleanup} (design D5 of
 * fix-terminal-receipt-convergence), each built the way the composition root builds it — {@link
 * TakeCommands} and {@link ServeCommands} go through the production {@code SlotWiringFactory},
 * {@code ServeAssembly} and {@code ServeRuntimeAssembly}; {@code run --resume} is the medium's own
 * resume runner over the world's production {@link TaskGit} bundle:
 *
 * <ul>
 *   <li>{@link #take} — {@code take <ref>}: {@code TakeDisposition}'s finished arms, {@code
 *       TakeLoadedBranchRoutes} under a fresh claim, {@code TakeEngineExecution} on a fresh task;
 *   <li>{@link #resume} — {@code run --resume}: {@code GitResumeContinuation} or {@code
 *       ContainerResumeOutcomes};
 *   <li>{@link #serve} — {@code serve}: the janitor's startup tick, whose branch pass is {@code
 *       DeliveredBranchPass}.
 * </ul>
 *
 * <p>The janitor ticks on its own thread, so {@link #serve} returns only once that tick has passed
 * the whole listing: the clone carries a sentinel branch, {@link #SENTINEL}, sorted after the task
 * branch, still {@code CompletedUncleaned} and owned by a task the tracker never finished. The
 * branch pass fetches it on every tick and leaves it alone, so the sentinel's fetch is the last act
 * of a tick that has already visited the task under test. The listing puts local heads before
 * remote-tracking refs, so the sentinel must be held as the task is: a world whose clone holds both
 * only at origin (a second clone) has its sentinel seeded by the first instance, and this seeds none.
 */
final class DeliveredCleanupEntries implements AppAssemblyFixture, ApplicationArgumentsFixture {

    /** A task the branch pass visits after {@link DeliveredCleanupWorld#TASK_ID}: refnames list in order. */
    static final String SENTINEL = 'ZZZ-1'

    private final DeliveredCleanupWorld world
    private final FetchCountingTracker counted

    DeliveredCleanupEntries(DeliveredCleanupWorld world) {
        this.world = world
        this.counted = new FetchCountingTracker(world.tracker)
    }

    /**
     * {@code take <ref>} on the world's task, through a command over {@code git} whose tracker adapter
     * is {@code tracker}; the exit code.
     */
    int take(TaskGit git = world.git, Tracker tracker = world.tracker) {
        def agent = FakeAgentSupport.propertiesFor('plain-round')
        def command = TakeCommands.of(newAssembly(agent, TakeCommands.slotTime()), git, world.clone, 'taskId', agent,
                trackerWiring(tracker), SandboxLifecyclePass.NONE, world.medium.containerTakeSupport())
        runExitCode(command, args('take', DeliveredCleanupWorld.TASK_ID, "--dir=${world.projectDir}"))
    }

    /** {@code run --resume} of the world's task, headless, through the medium's resume runner. */
    void resume() {
        def assembly = newAssembly(new ByteArrayInputStream(new byte[0]), sink(), FakeAgentSupport.propertiesFor('plain-round'))
        def order = new RunOrder(world.projectDir, null, DeliveredCleanupMedium.definition(), false)
        if (world.medium.host) {
            new GitResumeRunner(assembly, world.git, world.clone, 'taskId').run(order, DeliveredCleanupWorld.TASK_ID, null)
        } else {
            new ContainerResumeRunner(assembly, world.git, 'taskId', world.medium.supportFactory())
                    .run(order, DeliveredCleanupWorld.TASK_ID, null, DeliveredCleanupMedium.segments())
        }
    }

    /**
     * {@code serve} with its feed automaton captured, never run: the daemon's background duties
     * start, and this returns once the janitor's startup tick has passed the sentinel.
     */
    void serve() {
        if (!counted.seeded) {
            if (!world.holdsHead(SENTINEL) && !world.holdsTracking(SENTINEL)) {
                world.completedUncleaned(SENTINEL)
            }
            world.trackerReady(new TaskRef(SENTINEL))
            counted.seeded = true
        }
        int before = counted.sentinelFetches.get()
        def properties = testProperties(instanceName: 'gnomish-factory')
        def command = ServeCommands.of(newAssembly(properties), world.git, world.clone, 'taskId', properties,
                new ServeProperties(0, null, null, null, null, null, null, null, null, null), trackerWiring(counted),
                { automaton -> } as FeedAutomatonStarter, SandboxLifecyclePass.NONE, world.medium.containerTakeSupport(),
                LiveConsoleIO.onStderr())
        command.run(args('serve', "--dir=${world.projectDir}"))
        new PollingConditions(timeout: 20, delay: 0.05).eventually {
            assert counted.sentinelFetches.get() > before
        }
    }

    private static TrackerWiring trackerWiring(Tracker tracker) {
        new TrackerWiring([github: fakeFactory(tracker)], MapSecretsProvider.NONE,
        TrackerValidatorStub.acceptingGithubSource(), VirtualTimeEquipment.create())
    }

    private static PrintStream sink() {
        new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8')
    }

    /** The world's tracker as the adapter {@code serve} resolves, counting the branch pass's fetches of the sentinel. */
    private static final class FetchCountingTracker implements Tracker {

        @Delegate
        private final Tracker delegate
        final AtomicInteger sentinelFetches = new AtomicInteger()
        boolean seeded

        FetchCountingTracker(Tracker delegate) {
            this.delegate = delegate
        }

        @Override
        TrackerTask fetchTask(TaskRef ref) {
            TrackerTask task = delegate.fetchTask(ref)
            if (ref.id() == SENTINEL) {
                sentinelFetches.incrementAndGet()
            }
            task
        }
    }
}
