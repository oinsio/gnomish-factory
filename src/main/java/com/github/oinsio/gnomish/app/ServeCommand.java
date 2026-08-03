package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper;
import com.github.oinsio.gnomish.adapter.pipeline.TrackerSubsectionValidator;
import com.github.oinsio.gnomish.app.lease.ClaimBeat;
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.lease.HeartbeatProgress;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.serve.FeedAutomaton;
import com.github.oinsio.gnomish.app.serve.ServeShutdown;
import com.github.oinsio.gnomish.app.serve.SlotLedger;
import com.github.oinsio.gnomish.app.serve.TakeSlotRunner;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;

/**
 * {@code gnomish serve [--dir] [--slots] [--drain]} (FR2, FR4, FR12 of add-factory-serve; design
 * D3, D7): the continuously-running scheduler daemon, wired beside {@code run}/{@code status}/
 * {@code usage}/{@code take} with its own flag set (parsed by {@link ServeArgumentsParser}).
 * Loads the pipeline, requires a {@code tracker:} section exactly like {@link TakeCommand} (FR17
 * of add-tracker-port, via {@link TakeCommandSupport}), then runs the startup label-provisioning
 * smoke test (design D7): building the live {@link Tracker} via {@link TrackerAdapterFactory
 * #create} — the same call that provisions the gnomish labels — before any task is claimed. Any
 * {@link RuntimeException} from that call is startup failure (FR12): a clear error naming the
 * tracker binding is printed and {@link ServeExitCodeException} carries exit code 1 out, never a
 * direct {@code System.exit}.
 *
 * <p>Once the tracker is live, one {@link TakeHeartbeat} is built via {@link TakeHeartbeat#forRun}
 * (FR13): its {@link ClaimBeat} and {@link ClaimLossFlag} are the SAME instances threaded into
 * every slot's {@link TakeSlotRunner}, so one heartbeat thread beats every slot's held claim. The
 * reaper duty itself is a STANDING thread on its OWN interval, started here beside {@link
 * com.github.oinsio.gnomish.app.lease.StandingReaper} and independent of both the heartbeat's tick
 * and the feed automaton's state — a foreign claim still goes stale and is reaped while every slot
 * is busy (Full) or the feed is Idle-blocked (fix-reaper-idle-liveness FR1, FR5, design D1/D2;
 * design D3/D4, FR13's "Reaping while saturated" scenario). Its {@link HeartbeatProgress} joins the
 * assembly ONCE, before {@link TakeSlotRunner} is built, since the runner is reused for the
 * daemon's whole lifetime unlike {@link TakeCommand}'s per-invocation join. One {@link
 * TakeSlotRunner}, one {@link SlotLedger}, one {@link FeedAutomaton}, and one {@link ServeShutdown}
 * (sharing the SAME {@link SlotLedger}/{@link ClaimLossFlag}) are then assembled, and {@link
 * ServeShutdownWiring} drives either the drain path (FR10, NFR-O2, M3) or the ordinary forever
 * loop (FR11, design D9) — see its Javadoc for the full SIGTERM/shutdown-hook sequence.
 *
 * <p>Not a Spring {@code @Component}: {@link ManualRunRunner} constructs it imperatively, exactly
 * like {@link TakeCommand}.
 *
 * <p>Implements FR2, FR4, FR10, FR12, FR13, NFR-O2, M3, D3, D7 of add-factory-serve. Implements
 * FR11, D9, M3 of add-factory-serve.
 */
final class ServeCommand {

    private final ServeArgumentsParser argumentsParser = new ServeArgumentsParser();
    private final ManualRunAssembly assembly;
    private final Path worktreesRoot;
    private final String taskIdMdcKey;
    private final FactoryProperties factoryProperties;
    private final ServeProperties serveProperties;
    private final Clock clock;
    private final com.github.oinsio.gnomish.domain.engine.port.Clock feedClock;
    private final Map<String, TrackerAdapterFactory> trackerAdapterRegistry;
    private final Map<String, TrackerSubsectionValidator> trackerValidatorRegistry;
    private final FeedAutomatonStarter starter;

    /**
     * @param starter drives the assembled {@link FeedAutomaton} (task 5.1's test seam — see its
     *     Javadoc); production wiring passes {@link FeedAutomaton#run} itself
     */
    ServeCommand(
            ManualRunAssembly assembly,
            Path worktreesRoot,
            String taskIdMdcKey,
            FactoryProperties factoryProperties,
            ServeProperties serveProperties,
            Clock clock,
            com.github.oinsio.gnomish.domain.engine.port.Clock feedClock,
            Map<String, TrackerAdapterFactory> trackerAdapterRegistry,
            Map<String, TrackerSubsectionValidator> trackerValidatorRegistry,
            FeedAutomatonStarter starter) {
        this.assembly = assembly;
        this.worktreesRoot = worktreesRoot;
        this.taskIdMdcKey = taskIdMdcKey;
        this.factoryProperties = factoryProperties;
        this.serveProperties = serveProperties;
        this.clock = clock;
        this.feedClock = feedClock;
        this.trackerAdapterRegistry = trackerAdapterRegistry;
        this.trackerValidatorRegistry = trackerValidatorRegistry;
        this.starter = starter;
    }

    /**
     * Runs one {@code gnomish serve} invocation up to and through the startup smoke test, then
     * hands off to {@link ServeShutdownWiring} for either the drain path or the ordinary forever
     * loop — see the class Javadoc for the full SIGTERM/shutdown-hook sequence (FR11, design D9).
     *
     * @param args the raw application arguments, including the leading {@code serve} token
     * @throws UsageException if the flags are malformed or the project has no {@code tracker:}
     *     section (FR17)
     * @throws PipelineLoadFailedException if {@code .gnomish/} fails to load
     * @throws ServeExitCodeException if the startup label-provisioning smoke test fails (FR12)
     * @throws InterruptedException if drain's wait for slots to empty, or the forever loop's own
     *     wait for the feed thread to stop, is itself interrupted
     */
    void run(ApplicationArguments args) throws IOException, InterruptedException {
        ServeArguments serveArguments = argumentsParser.parse(args);
        PipelineDefinition definition = TakeCommandSupport.loadPipeline(serveArguments.dir(), trackerValidatorRegistry);
        TrackerConfig trackerConfig = TakeCommandSupport.requireTrackerConfig(definition);
        int effectiveSlots = serveArguments.slots() != null ? serveArguments.slots() : serveProperties.slots();
        InstanceId instanceId = InstanceId.generate(factoryProperties.instanceName());
        TrackerAdapterFactory factory = TakeCommandSupport.resolveFactory(trackerConfig, trackerAdapterRegistry);

        Tracker tracker = provisionTracker(factory, trackerConfig, instanceId);

        // FR13: one heartbeat/reaper over this run's tracker+config, shared by every slot — its
        // progress listener must join the assembly BEFORE TakeSlotRunner is built, since the runner
        // is reused for the daemon's whole lifetime (unlike TakeCommand's per-invocation join).
        TakeHeartbeat heartbeat = TakeHeartbeat.forRun(tracker, trackerConfig, new ThreadSleeper());
        ManualRunAssembly serveAssembly = assembly.withExtraListener(heartbeat.progress());

        SlotLedger slotLedger = new SlotLedger(effectiveSlots);
        TakeSlotRunner slotRunner = ServeAssembly.slotRunner(
                serveArguments,
                worktreesRoot,
                taskIdMdcKey,
                definition,
                trackerConfig,
                factory,
                tracker,
                instanceId,
                serveAssembly,
                heartbeat,
                clock);
        FeedAutomaton automaton = ServeAssembly.feedAutomaton(
                factoryProperties,
                serveProperties,
                feedClock,
                trackerConfig,
                tracker,
                instanceId,
                slotLedger,
                slotRunner);
        ServeShutdown shutdown =
                ServeAssembly.shutdown(slotLedger, heartbeat.flag(), serveProperties, heartbeat.standingReaper());
        ServeAssembly.worktreeJanitor(serveArguments, worktreesRoot, serveProperties, slotLedger)
                .start();
        // fix-reaper-idle-liveness FR1, FR5: the standing reaper runs for the daemon's whole
        // lifetime, exactly like WorktreeJanitor above — ServeShutdown.shutdown() stops it (FR4).
        heartbeat.standingReaper().start();

        if (serveArguments.drain()) {
            ServeShutdownWiring.runDrain(slotRunner, automaton, shutdown);
            return;
        }
        ServeShutdownWiring.runForever(automaton, shutdown, starter);
    }

    /**
     * FR12, design D7: the startup label-provisioning smoke test. {@code factory.create} is the
     * same call {@link TakeCommand} makes — for the GitHub adapter it provisions the four gnomish
     * labels on the configured repo before returning, so an unreachable repo or a bad token
     * surfaces here, before any task is claimed.
     */
    private Tracker provisionTracker(
            TrackerAdapterFactory factory, TrackerConfig trackerConfig, InstanceId instanceId) {
        try {
            return factory.create(trackerConfig, instanceId.value());
        } catch (RuntimeException startupFailure) {
            System.err.println("gnomish serve: startup failed provisioning tracker " + bindingDescription(trackerConfig)
                    + ": " + startupFailure.getMessage());
            throw new ServeExitCodeException(1);
        }
    }

    /** Names the binding in the startup-failure message: {@code type} plus {@code repo}, if configured. */
    private static String bindingDescription(TrackerConfig trackerConfig) {
        Object repo = trackerConfig.subsection().get("repo");
        return repo == null ? "'" + trackerConfig.type() + "'" : "'" + trackerConfig.type() + "' (" + repo + ")";
    }
}
