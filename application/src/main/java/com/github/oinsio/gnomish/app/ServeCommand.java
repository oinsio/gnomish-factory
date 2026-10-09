package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.serve.FeedAutomaton;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.status.AnchorLog;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>Once the tracker is live, {@link ServeRuntimeAssembly} builds the daemon over it — the one
 * {@link TakeHeartbeat} whose claim beat and loss flag every slot shares (FR13), the standing
 * reaper among the {@link com.github.oinsio.gnomish.app.serve.DaemonLoops} (fix-reaper-idle-liveness
 * FR1, FR5), the {@link ObservabilityWiring} (FR1, FR4, FR9, FR12 of add-serve-observability) and
 * the embedded dashboard when it is on. This command starts them — the observability, then the
 * page (D12 of supervise-daemon-loops-and-embed-dashboard), then the loops — and hands off to
 * {@link ServeShutdownWiring}, which drives the drain path (FR10, NFR-O2, M3) or the forever loop
 * (FR11, design D9) — see its Javadoc for the full sequence.
 *
 * <p>Implements FR2, FR4, FR10, FR11, FR12, FR13, NFR-O2, M3, D3, D7, D9 of add-factory-serve.
 * Implements FR1, FR4, FR7, FR8, FR9, FR12, D12 of add-serve-observability. Implements FR3, FR10 of
 * add-project-registry. Implements FR9, UX1, NFR-O2 of supervise-daemon-loops-and-embed-dashboard.
 */
final class ServeCommand {

    private static final Logger log = LoggerFactory.getLogger(ServeCommand.class);

    private final ServeArgumentsParser argumentsParser = new ServeArgumentsParser();
    private final ServeRuntimeAssembly runtimeAssembly;
    private final TaskGit git;
    private final ProjectScope scope;
    private final ServeProperties serveProperties;
    private final TrackerWiring trackerWiring;
    private final FeedAutomatonStarter starter;
    private final ConsoleIO errorConsole;
    /**
     * @param runtimeAssembly assembles the daemon runtime once the tracker is bound, over the
     *     command's fixed equipment (design D7 of collapse-composition-roots)
     * @param scope the registered clone {@code --dir} names — the directory the daemon works in — and
     *     the instance id it claims under, which names the project (FR3, FR10 of add-project-registry)
     * @param errorConsole the console owner bound to {@code stderr} (FR5, FR6 of
     *     harden-untrusted-text-sinks): the two startup-failure sentences go out on its human
     *     path, since each carries a message from a tracker or a git remote, and so does the
     *     dashboard's page line (UX1 of supervise-daemon-loops-and-embed-dashboard) — the daemon's
     *     one operator console
     * @param starter drives the assembled {@link FeedAutomaton} (task 5.1's test seam — see its
     *     Javadoc); production wiring passes {@link FeedAutomaton#run} itself
     */
    ServeCommand(
            ServeRuntimeAssembly runtimeAssembly,
            TaskGit git,
            ProjectScope scope,
            ServeProperties serveProperties,
            TrackerWiring trackerWiring,
            FeedAutomatonStarter starter,
            ConsoleIO errorConsole) {
        this.runtimeAssembly = runtimeAssembly;
        this.git = git;
        this.scope = scope;
        this.serveProperties = serveProperties;
        this.trackerWiring = trackerWiring;
        this.starter = starter;
        this.errorConsole = errorConsole;
    }

    /**
     * Runs one {@code gnomish serve} invocation up to the startup smoke test, then hands off to
     * {@link ServeShutdownWiring} — see the class Javadoc for the full wiring/shutdown sequence.
     *
     * @param args the raw application arguments, including the leading {@code serve} token
     * @throws UsageException if the flags are malformed or the project has no {@code tracker:}
     *     section (FR17)
     * @throws PipelineLoadFailedException if {@code .gnomish/} fails to load
     * @throws ServeExitCodeException if the startup label-provisioning smoke test fails (FR12), or
     *     origin's default branch cannot be established or refreshed (FR5, FR13 of
     *     add-base-ref-resolution) — exit code 1 either way
     * @throws InterruptedException if drain's wait for slots to empty, or the forever loop's own
     *     wait for the feed thread to stop, is itself interrupted
     */
    void run(ApplicationArguments args) throws IOException, InterruptedException {
        ServeArguments serveArguments =
                argumentsParser.parse(args, scope.registeredClone(), serveProperties.dashboard());
        TrustedTierStartup.StartupLaw startupLaw = bindStartupLaw(serveArguments.dir());
        PipelineDefinition definition = startupLaw.definition();
        // FR13, D15 of add-base-ref-resolution: bound once here, threaded to every slot's fresh
        // claim — never re-read per claim.
        TrustedBaseContext trustedBase = new TrustedBaseContext(startupLaw.base(), startupLaw.defaultBranch());
        TrackerConfig trackerConfig = TakeCommandSupport.requireTrackerConfig(definition);
        int effectiveSlots = serveArguments.slots() != null ? serveArguments.slots() : serveProperties.slots();
        InstanceId instanceId = scope.mintInstanceId();
        TrackerAdapterFactory factory = trackerWiring.resolveFactory(trackerConfig);

        // FR12, D7: the startup smoke test stays here (the command owns the exit-code failure);
        // the runtime assembly wires everything else off the live tracker (process-invariants.md).
        Tracker liveTracker = provisionTracker(factory, trackerConfig, instanceId);
        // FR8, design D8 of collapse-composition-roots: what this invocation bound, handed to the
        // runtime assembly whole — which derives the one bound tracker below it over the
        // health-wrapped tracker, so the raw one reaches nothing further.
        ServeRuntime runtime = runtimeAssembly.assemble(
                serveArguments,
                new BoundTracker(definition, trustedBase, trackerConfig, factory, liveTracker, instanceId),
                effectiveSlots);

        // FR2 of harden-logging-observability: the start anchor names the configuration the daemon
        // actually resolved — flags, properties and defaults folded together, the page included
        // (NFR-O2 of supervise-daemon-loops-and-embed-dashboard) — so no post-mortem re-derives it.
        Path page =
                runtime.dashboard().map(w -> w.outputFile().toAbsolutePath()).orElse(null);
        AnchorLog.serveStarted(new AnchorLog.ServeConfig(
                instanceId.value(),
                effectiveSlots,
                trackerConfig.wipLimit(),
                serveProperties.idlePollInterval(),
                serveProperties.sigtermGrace(),
                serveArguments.dashboard(),
                page));

        runtime.observability().start(); // FR1, FR12: the snapshot writer and the `started` line
        // D12, UX1: after the writer, so the first render finds a snapshot; named before any claim.
        runtime.dashboard().ifPresent(watch -> {
            watch.start();
            errorConsole.print("gnomish serve: dashboard -> " + page + ConsoleIO.LINE_END);
        });
        // D9: the reaper, the janitor (FR14) and the sweep tick (FR6 of add-serve-sandbox-lifecycle)
        // start after the `started` ledger line so no sweep line precedes it; the shutdown stops them.
        runtime.daemonLoops().start();

        if (serveArguments.drain()) {
            ServeShutdownWiring.runDrain(
                    runtime.slotRunner(),
                    runtime.automaton(),
                    runtime.shutdown(),
                    runtime.observability(),
                    runtime.dashboard());
            return;
        }
        ServeShutdownWiring.runForever(
                runtime.automaton(), runtime.shutdown(), starter, runtime.observability(), runtime.dashboard());
    }

    /**
     * FR13, D14/D15 of add-base-ref-resolution: the definition comes from the refreshed default
     * branch of origin, read from git objects — never from the clone's checkout. A default branch
     * that cannot be established or refreshed is startup failure of the same class as an
     * unreachable tracker: the plain sentence on the console, exit code 1, nothing claimed.
     */
    private TrustedTierStartup.StartupLaw bindStartupLaw(Path dir) throws IOException {
        try {
            return trackerWiring.bindStartupLaw(dir, git.baseRefs());
        } catch (DefaultBranchUnboundException unbound) {
            // throwable-not-subject: TrustedTierStartup logged the one ERROR of this failure; the
            //     console line is the operator's copy of its sentence.
            errorConsole.print("gnomish serve: startup failed: " + unbound.getMessage() + ConsoleIO.LINE_END);
            throw new ServeExitCodeException(1);
        }
    }

    /**
     * FR12, design D7: the startup label-provisioning smoke test — the same {@link
     * TrackerWiring#resolveTracker} funnel {@link TakeCommand} resolves through (FR4, design D2
     * of fix-claim-epoch-fence), so an unreachable repo or bad token surfaces here, before any task
     * is claimed, and every slot's claim lands in the same tenure record its writers stamp from.
     */
    private Tracker provisionTracker(
            TrackerAdapterFactory factory, TrackerConfig trackerConfig, InstanceId instanceId) {
        try {
            return trackerWiring.resolveTracker(factory, trackerConfig, instanceId, git.epochs());
        } catch (RuntimeException startupFailure) {
            // The operator's console gets the plain sentence; the log file gets the same failure
            // with its stack and cause chain (FR2, FR7 of harden-logging-observability). Until now
            // a startup that died here left nothing at all in the log — the one record of why the
            // daemon never came up went to a terminal nobody keeps.
            log.error(
                    OperatorEvent.SERVE_TRACKER_PROVISION_FAILED.head()
                            + "gnomish serve: startup failed provisioning tracker {}",
                    bindingDescription(trackerConfig),
                    startupFailure);
            errorConsole.print("gnomish serve: startup failed provisioning tracker " + bindingDescription(trackerConfig)
                    + ": " + startupFailure.getMessage() + ConsoleIO.LINE_END);
            throw new ServeExitCodeException(1);
        }
    }

    /** Names the binding in the failure message: {@code type} plus {@code repo}, if configured. */
    private static String bindingDescription(TrackerConfig trackerConfig) {
        Object repo = trackerConfig.subsection().get("repo");
        return repo == null ? "'" + trackerConfig.type() + "'" : "'" + trackerConfig.type() + "' (" + repo + ")";
    }
}
