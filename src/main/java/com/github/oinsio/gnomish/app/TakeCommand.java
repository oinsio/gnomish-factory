package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.adapter.pipeline.TrackerSubsectionValidator;
import com.github.oinsio.gnomish.app.lease.MonotonicTime;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.ApplicationArguments;

/**
 * {@code gnomish take [<ref>]} (FR9, FR10, FR17 of add-tracker-port; design D4, D15, D16): the
 * single-task tracker CLI, wired beside {@code run}/{@code status}/{@code usage} with its own flag set
 * (parsed by {@link TakeArgumentsParser}). Given a {@code <ref>}, dispatches to explicit mode; bare,
 * to bare-auto mode — both via {@link TakeDispatcher}. The resulting {@link TakeResult} is converted
 * to a process exit code via {@link TakeExitCodeMapper} and surfaced by throwing {@link
 * TakeExitCodeException} — never a direct {@code System.exit} (project convention).
 *
 * <p>Pipeline load, the FR17 no-{@code tracker:}-section refusal, and tracker-adapter resolution are
 * delegated to {@link TakeCommandSupport}; the explicit/bare dispatch to {@link TakeDispatcher} — both
 * split out for file size. A live {@link Tracker} and the {@link TakeHeartbeat} over it are resolved
 * per invocation, never as Spring {@code @Bean}s (which tracker adapter is active depends on the
 * project's own config, read per invocation like {@link PipelineDefinition} itself). The heartbeat's
 * standing reaper is started right after the heartbeat is built and stopped in a {@code finally}
 * around dispatch, so it runs for the whole invocation regardless of how it ends (fix-reaper-idle-
 * liveness FR1, FR5).
 *
 * <p>Not a Spring {@code @Component}: {@link ManualRunRunner} constructs it imperatively (via {@link
 * TakeCommandFactory}), exactly like {@link GitModeRunner}/{@link GitResumeRunner}.
 *
 * <p>Implements FR9, FR10, FR17, D4, D15, D16 of add-tracker-port.
 */
final class TakeCommand {

    private static final Logger log = LoggerFactory.getLogger(TakeCommand.class);

    private final TakeArgumentsParser argumentsParser = new TakeArgumentsParser();
    private final ManualRunAssembly assembly;
    private final Path worktreesRoot;
    private final String taskIdMdcKey;
    private final FactoryProperties factoryProperties;
    private final Clock clock;
    private final Map<String, TrackerAdapterFactory> trackerAdapterRegistry;
    private final Map<String, TrackerSubsectionValidator> trackerValidatorRegistry;
    private final Sleeper heartbeatSleeper;
    private final Sleeper reaperSleeper;
    private final MonotonicTime heartbeatMonotonicTime;
    private final TakeoverConfirmation takeoverConfirmation;
    private final ServeProperties serveProperties;

    /**
     * The canonical construction; {@link TakeCommandFactory} supplies the {@code heartbeatSleeper}
     * (task 6.1), {@code reaperSleeper} (fix-reaper-idle-liveness FR5), {@code
     * heartbeatMonotonicTime} (task 6.6), {@code takeoverConfirmation} (task 6.2), and {@code
     * serveProperties} (task 6.2) test seams, defaulting them to production values for the {@link
     * ManualRunRunner} wiring.
     *
     * @param assembly the shared engine/ports assembly, reused from the manual-run path; never null
     * @param worktreesRoot the root directory under which per-task worktrees are created; never null
     * @param taskIdMdcKey the MDC key set once a resume bootstrap succeeds; never null
     * @param factoryProperties supplies the instance-name half of the minted {@link InstanceId} and
     *     the abort-backoff base/cap defaults (design D5, D6, D10); never null
     * @param clock supplies "now" for bare-mode backoff and the abort timestamp; never null
     * @param trackerAdapterRegistry known tracker adapter factories, keyed by {@code tracker.type}
     * @param trackerValidatorRegistry known adapter subsection validators, keyed by {@code
     *     tracker.type}, so {@code take} rejects a malformed {@code tracker.<type>} at load time (FR17)
     * @param heartbeatSleeper the beat-interval sleeper injected into the per-invocation heartbeat (FR1)
     * @param reaperSleeper the standing reaper's OWN interval sleeper, independent of {@code
     *     heartbeatSleeper} so a test can drive the two threads' ticks separately (fix-reaper-idle-
     *     liveness FR5); production wiring passes the same sleeper for both, which is harmless
     * @param heartbeatMonotonicTime the monotonic time the per-invocation reaper's TTL is measured on
     *     (FR4, M2)
     * @param takeoverConfirmation the pre-claim {@code Working}-takeover confirmation seam (FR6, D9)
     * @param serveProperties supplies batch mode's concurrency limit N ({@code factory.serve.slots}
     *     — FR2 of add-factory-serve: "the N limit applies to batch and serve", no separate batch
     *     flag); never null
     */
    TakeCommand(
            ManualRunAssembly assembly,
            Path worktreesRoot,
            String taskIdMdcKey,
            FactoryProperties factoryProperties,
            Clock clock,
            Map<String, TrackerAdapterFactory> trackerAdapterRegistry,
            Map<String, TrackerSubsectionValidator> trackerValidatorRegistry,
            Sleeper heartbeatSleeper,
            Sleeper reaperSleeper,
            MonotonicTime heartbeatMonotonicTime,
            TakeoverConfirmation takeoverConfirmation,
            ServeProperties serveProperties) {
        this.assembly = assembly;
        this.worktreesRoot = worktreesRoot;
        this.taskIdMdcKey = taskIdMdcKey;
        this.factoryProperties = factoryProperties;
        this.clock = clock;
        this.trackerAdapterRegistry = trackerAdapterRegistry;
        this.trackerValidatorRegistry = trackerValidatorRegistry;
        this.heartbeatSleeper = heartbeatSleeper;
        this.reaperSleeper = reaperSleeper;
        this.heartbeatMonotonicTime = heartbeatMonotonicTime;
        this.takeoverConfirmation = takeoverConfirmation;
        this.serveProperties = serveProperties;
    }

    /**
     * Runs one {@code gnomish take} invocation to its terminal result and throws the corresponding
     * {@link TakeExitCodeException}.
     *
     * @param args the raw application arguments, including the leading {@code take} token
     * @throws UsageException if the flags are malformed, the project has no {@code tracker:} section
     *     (FR17), or {@code tracker.type} names no registered adapter
     * @throws PipelineLoadFailedException if {@code .gnomish/} fails to load
     * @throws TakeExitCodeException always, on a completed run — carrying the computed exit code (D16)
     * @throws InterruptedException if a batch run is interrupted while waiting on its scheduler
     */
    void run(ApplicationArguments args) throws IOException, InterruptedException {
        try {
            TakeArguments takeArguments = argumentsParser.parse(args);
            PipelineDefinition definition =
                    TakeCommandSupport.loadPipeline(takeArguments.dir(), trackerValidatorRegistry);
            TrackerConfig trackerConfig = TakeCommandSupport.requireTrackerConfig(definition);
            InstanceId instanceId = InstanceId.generate(factoryProperties.instanceName());
            TrackerAdapterFactory factory = TakeCommandSupport.resolveFactory(trackerConfig, trackerAdapterRegistry);
            Tracker tracker = factory.create(trackerConfig, instanceId.value());
            List<String> credentialEnvVarsToScrub = factory.credentialEnvVars();

            // Task 6.1 of add-claim-heartbeat (FR1): the instance heartbeat is built once per
            // invocation over this run's tracker and beat/TTL config; its progress listener is fanned
            // into the engine run's listener composite and its lifecycle is driven at the claim choke
            // point (TakeClaimAndWork#dispatchAfterClaim).
            TakeHeartbeat heartbeat = TakeHeartbeat.forRun(
                    tracker, trackerConfig, heartbeatSleeper, reaperSleeper, heartbeatMonotonicTime);
            // fix-reaper-idle-liveness FR1, FR5: the standing reaper runs on its own thread for the
            // whole invocation, independent of the heartbeat tick, so a stale-claim sweep is not
            // starved by a stuck or slow beat; it is stopped exactly once, however the run ends
            // (normal completion, TakeExitCodeException, or any other exception).
            heartbeat.standingReaper().start();
            try {
                ManualRunAssembly takeAssembly = assembly.withExtraListener(heartbeat.progress());
                var dispatcher = new TakeDispatcher(
                        worktreesRoot,
                        taskIdMdcKey,
                        factoryProperties,
                        clock,
                        trackerAdapterRegistry,
                        takeoverConfirmation);
                TakeRefDispatch.run(
                        dispatcher,
                        takeArguments,
                        definition,
                        trackerConfig,
                        tracker,
                        instanceId,
                        credentialEnvVarsToScrub,
                        factory,
                        takeAssembly,
                        heartbeat,
                        serveProperties,
                        log);
            } finally {
                heartbeat.standingReaper().stop();
            }
        } finally {
            MDC.remove(taskIdMdcKey);
        }
    }
}
