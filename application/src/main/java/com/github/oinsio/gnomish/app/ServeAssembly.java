package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.lease.LivenessOracle;
import com.github.oinsio.gnomish.app.lease.StandingReaper;
import com.github.oinsio.gnomish.app.port.git.BaseRefGit;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerHealthTracker;
import com.github.oinsio.gnomish.app.sandboxlifecycle.ObservedSandboxLifecyclePass;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickListener;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickLog;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictListener;
import com.github.oinsio.gnomish.app.serve.DirtyNotifier;
import com.github.oinsio.gnomish.app.serve.FeedAssembly;
import com.github.oinsio.gnomish.app.serve.FeedAutomaton;
import com.github.oinsio.gnomish.app.serve.ForwardingDirtyNotifier;
import com.github.oinsio.gnomish.app.serve.IdleTiming;
import com.github.oinsio.gnomish.app.serve.RealProcessTreeKiller;
import com.github.oinsio.gnomish.app.serve.RemoteOutageClosedOutage;
import com.github.oinsio.gnomish.app.serve.RemoteOutageGate;
import com.github.oinsio.gnomish.app.serve.RemoteOutageGates;
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass;
import com.github.oinsio.gnomish.app.serve.SandboxLifecycleTick;
import com.github.oinsio.gnomish.app.serve.ServeShutdown;
import com.github.oinsio.gnomish.app.serve.SlotLedger;
import com.github.oinsio.gnomish.app.serve.TakeSlotRunner;
import com.github.oinsio.gnomish.app.serve.WorktreeJanitor;
import com.github.oinsio.gnomish.domain.engine.time.SystemClock;
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Random;
import java.util.function.Consumer;

/**
 * The leaf builders {@link ServeRuntimeAssembly} composes into the {@code serve} daemon runtime,
 * over the daemon's fixed equipment — the factory and serve properties and the engine clock —
 * which every builder that reads it takes from these fields rather than as a parameter per call
 * (design D7 of collapse-composition-roots, Fowler's <em>Combine Functions into Class</em>). Each
 * builder takes only its per-call job, and a builder fed from the {@link BoundTracker} takes
 * exactly the members it uses. Split from {@link ServeRuntimeAssembly} so the specs can drive each
 * builder in isolation.
 *
 * <p>Implements FR2, FR11, FR13, D9 of add-factory-serve; D7 of collapse-composition-roots.
 */
final class ServeAssembly {

    private final FactoryProperties factoryProperties;
    private final ServeProperties serveProperties;
    private final com.github.oinsio.gnomish.domain.engine.port.Clock feedClock;

    /** The engine clock is the one the feed, the slot ledger and the tracker-health decorator read. */
    ServeAssembly(
            FactoryProperties factoryProperties,
            ServeProperties serveProperties,
            com.github.oinsio.gnomish.domain.engine.port.Clock feedClock) {
        this.factoryProperties = factoryProperties;
        this.serveProperties = serveProperties;
        this.feedClock = feedClock;
    }

    /** FR8, D12 of add-serve-observability: the health decorator every downstream caller shares. */
    TrackerHealthTracker trackerHealth(Tracker liveTracker) {
        return new TrackerHealthTracker(liveTracker, feedClock);
    }

    /** FR13: the one slot ledger the feed, the slot runner and the janitor share. */
    SlotLedger slotLedger(int effectiveSlots, DirtyNotifier dirtyNotifier) {
        return new SlotLedger(effectiveSlots, feedClock, dirtyNotifier);
    }

    /**
     * FR14, NFR-R3 of add-base-ref-resolution: the one gate the slot runner opens and the feed
     * consults; {@code onTransition} and {@code ledgerSink} are the snapshot and ledger hooks.
     */
    RemoteOutageGate remoteOutageGate(
            BaseRefGit baseRefs, Path cloneDir, Runnable onTransition, Consumer<RemoteOutageClosedOutage> ledgerSink) {
        return RemoteOutageGates.system(
                baseRefs,
                cloneDir,
                serveProperties.idlePollInterval(),
                serveProperties.remoteProbeIntervalCap(),
                serveProperties.remoteSustainedOpenThreshold(),
                onTransition,
                ledgerSink);
    }

    /** FR1, FR4, FR7, FR9, FR12 of add-serve-observability: the snapshot writer and ledger. */
    ObservabilityWiring observability(
            InstanceId instanceId,
            Path homeDir,
            ForwardingDirtyNotifier dirtyNotifier,
            Clock clock,
            SnapshotSources sources) {
        return ObservabilityAssembly.assemble(
                factoryProperties, serveProperties, instanceId, homeDir, dirtyNotifier, clock, sources);
    }

    /**
     * FR13: the one slot runner every slot shares, over the daemon's one {@code wiring} — so the
     * heartbeat's {@code ClaimBeat}/{@code ClaimLossFlag} in its tenure are shared by every slot.
     */
    static TakeSlotRunner slotRunner(
            ServeArguments serveArguments,
            PipelineDefinition definition,
            Tracker tracker,
            InstanceId instanceId,
            SlotWiring wiring) {
        // The serve arguments become the slots' run order here and nowhere else (D1 of
        // introduce-take-order): serve is unconditionally non-interactive (FR4 of
        // add-factory-serve), takes no --base, and always salvages.
        var run = new RunOrder(serveArguments.dir(), null, definition, RunArguments.InteractiveMode.NONE, false);
        return new TakeSlotRunner(wiring, run, tracker, instanceId);
    }

    FeedAutomaton feedAutomaton(
            TrackerConfig trackerConfig,
            Tracker tracker,
            InstanceId instanceId,
            SlotLedger slotLedger,
            TakeSlotRunner slotRunner,
            DirtyNotifier dirtyNotifier,
            RemoteOutageGate remoteOutageGate) {
        FactoryProperties.Tracker trackerProperties = factoryProperties.tracker();
        // D7 of add-parameter-count-gate: the feed's collaborators are built by its assembly object,
        // constructed here over the daemon's timing equipment.
        var assembly = new FeedAssembly(
                new ThreadSleeper(),
                feedClock,
                new IdleTiming(
                        serveProperties.idlePollInterval(),
                        trackerProperties.abortBackoffBase(),
                        trackerProperties.abortBackoffCap(),
                        new Random()),
                trackerConfig.wipLimit());
        return assembly.feedAutomaton(tracker, instanceId, slotLedger, slotRunner, dirtyNotifier, remoteOutageGate);
    }

    /**
     * FR11, D9: the SIGTERM shutdown coordinator, sharing {@code slotLedger} and {@code
     * claimLossFlag} with the {@link FeedAutomaton}/{@link TakeSlotRunner}, so flagging a slot's
     * claim here reacts at the SAME round-boundary check every other claim-loss reaches.
     */
    ServeShutdown shutdown(SlotLedger slotLedger, ClaimLossFlag claimLossFlag, StandingReaper standingReaper) {
        return new ServeShutdown(
                slotLedger, claimLossFlag, serveProperties.sigtermGrace(), new RealProcessTreeKiller(), standingReaper);
    }

    /**
     * FR14, D10: the worktree janitor, disposing through the task-git port's own bound disposer
     * (task 4.4 of split-into-modules — it used to build the git-subprocess one here). Held tasks
     * are read fresh from {@code slotLedger} every tick, so a task claimed after the janitor starts
     * is still protected.
     */
    WorktreeJanitor worktreeJanitor(
            ServeArguments serveArguments, Path worktreesRoot, SlotLedger slotLedger, TaskGit git) {
        var disposal = git.worktrees().environmentDisposal(serveArguments.dir(), worktreesRoot);
        return new WorktreeJanitor(
                worktreesRoot,
                serveArguments.dir(),
                serveProperties.worktreeAgeThreshold(),
                disposal,
                new SystemClock(),
                new ThreadSleeper(),
                slotLedger::occupiedRefs);
    }

    /**
     * FR6, NFR-P1, design D7 of add-serve-sandbox-lifecycle: the sweep-lifecycle tick, its own
     * virtual thread beside the worktree janitor's. {@code sandboxLifecyclePass} is {@link
     * SandboxLifecyclePass#NONE} on a host-only install, so the tick runs but is a no-op.
     *
     * <p>NFR-O1, NFR-O2 of add-serve-sandbox-lifecycle: the daemon — and only the daemon — brackets
     * each pass as an observed tick, so the snapshot's {@code vitals.sweep} and the ledger's sweep
     * lines both come from the same evaluation the scheduler already runs. A host-only install is
     * deliberately left UNobserved: an all-zero vital would report on a subsystem that does not
     * exist on that host.
     */
    SandboxLifecycleTick sandboxLifecycleTick(
            ServeArguments serveArguments,
            SandboxLifecyclePass sandboxLifecyclePass,
            LivenessOracle livenessOracle,
            SweepTickLog sweepTickLog,
            SweepVerdictListener sweepVerdictSink,
            SweepTickListener sweepTickSink) {
        SandboxLifecyclePass observed = Objects.equals(sandboxLifecyclePass, SandboxLifecyclePass.NONE)
                ? sandboxLifecyclePass
                : new ObservedSandboxLifecyclePass(sandboxLifecyclePass, sweepTickLog, sweepVerdictSink, sweepTickSink);
        return new SandboxLifecycleTick(
                observed,
                livenessOracle,
                serveArguments.dir(),
                serveProperties.sandboxSweepInterval(),
                new ThreadSleeper(),
                new SystemClock());
    }
}
