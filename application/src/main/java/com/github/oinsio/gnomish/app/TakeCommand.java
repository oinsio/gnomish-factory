package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import com.github.oinsio.gnomish.status.MdcEventListener;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.ApplicationArguments;

/**
 * {@code gnomish take [<ref>]} (FR9, FR10, FR17 of add-tracker-port; design D4, D15, D16): the
 * single-task tracker CLI, wired beside {@code run}/{@code status}/{@code usage} with its own flag set
 * (parsed by {@link TakeArgumentsParser}). Given a {@code <ref>}, dispatches to explicit mode; bare,
 * to bare-auto mode — both via {@link TakeDispatcher}. The resulting {@link
 * com.github.oinsio.gnomish.app.take.TakeResult} is converted to a process exit code via {@link
 * com.github.oinsio.gnomish.app.take.TakeExitCodeMapper} and surfaced by throwing {@link
 * TakeExitCodeException} — never a direct {@code System.exit} (project convention).
 *
 * <p>Pipeline load, the FR17 no-{@code tracker:}-section refusal, and tracker-adapter resolution are
 * delegated to {@link TakeCommandSupport}; tracker-adapter resolution to {@link TrackerWiring};
 * the explicit/bare dispatch to {@link TakeDispatcher} — all
 * split out for file size. A live {@link Tracker} and the {@link TakeHeartbeat} over it are resolved
 * per invocation, never as Spring {@code @Bean}s (which tracker adapter is active depends on the
 * project's own config, read per invocation like {@link PipelineDefinition} itself). The heartbeat's
 * standing reaper is started right after the heartbeat is built and stopped in a {@code finally}
 * around dispatch, so it runs for the whole invocation regardless of how it ends (fix-reaper-idle-
 * liveness FR1, FR5).
 *
 * <p>Implements FR9, FR10, FR17, D4, D15, D16 of add-tracker-port; FR3, FR10 of add-project-registry;
 * FR18 of supervise-daemon-loops-and-embed-dashboard.
 */
final class TakeCommand {

    private static final Logger log = LoggerFactory.getLogger(TakeCommand.class);

    private final TakeArgumentsParser argumentsParser = new TakeArgumentsParser();
    private final SlotWiringFactory slotWiringFactory;
    private final TaskGit git;
    private final FactoryProperties factoryProperties;
    private final ProjectScope scope;
    private final TrackerWiring trackerWiring;
    private final TakeCommandSeams seams;
    private final SandboxLifecyclePass sandboxLifecyclePass;

    /**
     * The canonical construction; production wiring passes {@link TakeCommandSeams#defaults} with
     * the installation's own {@link ServeProperties}, a spec layers on the seams it overrides.
     *
     * @param slotWiringFactory builds the invocation's one {@link SlotWiring} once the tracker is
     *     bound — the equipment {@code take} shares with {@code serve} (design D9 of
     *     collapse-composition-roots)
     * @param git the task-branch git port shared with the manual-run path; never null
     * @param factoryProperties supplies the abort-backoff base/cap defaults (design D5, D6, D10);
     *     never null
     * @param scope the registered clone {@code --dir} names — the directory the invocation works in
     *     — and the minted {@link InstanceId}, which names the project (FR3, FR10 of
     *     add-project-registry); never null
     * @param trackerWiring the one owner of the adapter registry, the credential seam and the
     *     definition source (design D2 of collapse-composition-roots): binds the startup law from the
     *     refreshed default branch and resolves the invocation's tracker
     * @param seams the heartbeat and reaper sleepers (FR1; fix-reaper-idle-liveness FR5), the
     *     reaper's monotonic time (FR4, M2), the takeover confirmation (FR6, D9) and batch mode's
     *     {@link ServeProperties} (FR2 of add-factory-serve: "the N limit applies to batch and
     *     serve") and the heartbeat's time equipment; "now" for bare-mode backoff and takeover is
     *     the slot wiring's own time ({@link SlotWiring#time()})
     * @param sandboxLifecyclePass the pre-dispatch sweep-lifecycle evaluation seam (FR6, NFR-O4 of
     *     add-serve-sandbox-lifecycle); {@code SandboxLifecyclePass.NONE} on a host-only install
     */
    TakeCommand(
            SlotWiringFactory slotWiringFactory,
            TaskGit git,
            FactoryProperties factoryProperties,
            ProjectScope scope,
            TrackerWiring trackerWiring,
            TakeCommandSeams seams,
            SandboxLifecyclePass sandboxLifecyclePass) {
        this.slotWiringFactory = slotWiringFactory;
        this.git = git;
        this.factoryProperties = factoryProperties;
        this.scope = scope;
        this.trackerWiring = trackerWiring;
        this.seams = seams;
        this.sandboxLifecyclePass = sandboxLifecyclePass;
    }

    /**
     * Runs one {@code gnomish take} invocation to its terminal result and throws the corresponding
     * {@link TakeExitCodeException}.
     *
     * @param args the raw application arguments, including the leading {@code take} token
     * @throws UsageException if the flags are malformed, the project has no {@code tracker:} section
     *     (FR17), or {@code tracker.type} names no registered adapter
     * @throws PipelineLoadFailedException if {@code .gnomish/} fails to load
     * @throws DefaultBranchUnboundException if origin's default branch cannot be established or
     *     refreshed at startup — exit code 1, a failure outside a claimed run (FR5, FR13 of
     *     add-base-ref-resolution)
     * @throws TakeExitCodeException always, on a completed run — carrying the computed exit code (D16)
     * @throws InterruptedException if a batch run is interrupted while waiting on its scheduler
     */
    void run(ApplicationArguments args) throws IOException, InterruptedException {
        try {
            TakeArguments takeArguments = argumentsParser.parse(args, scope.registeredClone());
            // FR13, D14/D15 of add-base-ref-resolution: the definition comes from the refreshed
            // default branch of origin, read from git objects — never from the clone's checkout.
            TrustedTierStartup.StartupLaw startupLaw =
                    trackerWiring.bindStartupLaw(takeArguments.dir(), git.baseRefs());
            PipelineDefinition definition = startupLaw.definition();
            // FR13, D15 of add-base-ref-resolution: bound once here, threaded to every fresh
            // claim this invocation makes — never re-read per claim.
            TrustedBaseContext trustedBase = new TrustedBaseContext(startupLaw.base(), startupLaw.defaultBranch());
            TrackerConfig trackerConfig = TakeCommandSupport.requireTrackerConfig(definition);
            InstanceId instanceId = scope.mintInstanceId();
            TrackerAdapterFactory factory = trackerWiring.resolveFactory(trackerConfig);
            // FR4, design D2 of fix-claim-epoch-fence: the one funnel a claiming command resolves
            // through — the adapter stamps from the bundle's book and the decorator fills the same
            // book, so no assembly can stamp one record and record another.
            Tracker tracker = trackerWiring.resolveTracker(factory, trackerConfig, instanceId, git.epochs());
            // FR8, design D8 of collapse-composition-roots: what this invocation bound, as one value
            // from here to the last relay of the dispatch chain.
            var bound = new BoundTracker(definition, trustedBase, trackerConfig, factory, tracker, instanceId);

            // Task 6.1 of add-claim-heartbeat (FR1): the instance heartbeat is built once per
            // invocation over this run's tracker and beat/TTL config; its progress listener is fanned
            // into the engine run's listener composite and its lifecycle is driven at the claim choke
            // point (TakeClaimAndWork#dispatchAfterClaim).
            TakeHeartbeat heartbeat = TakeHeartbeat.forRun(
                    tracker, trackerConfig, seams.time(), seams.reaperSleeper(), seams.heartbeatMonotonicTime());
            // The take side's one slot wiring (design "Where a SlotWiring is built" of
            // introduce-slot-wiring; built by the factory of design D9 of collapse-composition-roots):
            // built once per invocation, as soon as the heartbeat exists, and shared by explicit,
            // bare and batch mode. One abort handler for the invocation — it is a stateless record
            // over the tracker and clock, and the tracker is the same for every ref. Built before the
            // reaper starts, so its MDC key is in hand for the finally that clears it.
            SlotWiring wiring = slotWiringFactory.slotWiring(bound, git, heartbeat);
            try {
                // fix-reaper-idle-liveness FR1, FR5: the standing reaper runs on its own thread for
                // the whole invocation, independent of the heartbeat tick, so a stale-claim sweep is
                // not starved by a stuck or slow beat; it is stopped exactly once, however the run
                // ends (normal completion, TakeExitCodeException, or any other exception).
                heartbeat.standingReaper().start();
                try {
                    // FR6, NFR-O4 of add-serve-sandbox-lifecycle: one startup sweep pass before
                    // dispatch, sharing the heartbeat's own liveness oracle (no second tracker
                    // listing, NFR-C2). Inside the try: the reaper is already running, so a pass that
                    // throws (a Docker outage aborts it, NFR-R1) must still stop the reaper thread —
                    // and must never fail the take, since the sweep is hygiene, not the task.
                    TakeCommandSupport.sweepSandboxLifecycle(
                            sandboxLifecyclePass,
                            takeArguments.dir(),
                            heartbeat.livenessOracle().evaluate(),
                            log);
                    var dispatcher =
                            new TakeDispatcher(wiring, factoryProperties, trackerWiring, seams.takeoverConfirmation());
                    TakeRefDispatch.run(dispatcher, takeArguments, bound, seams.serveProperties(), log);
                } finally {
                    heartbeat.standingReaper().stop();
                }
            } finally {
                MDC.remove(wiring.taskIdMdcKey());
            }
        } finally {
            // FR8: backstop — an abort thrown out of the engine skips TaskFinished, so the
            // stage/attempt keys are cleared here alongside taskId.
            MdcEventListener.clearAttemptScope();
        }
    }
}
