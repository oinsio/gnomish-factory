package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.lease.InstanceHeartbeat;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.TrackerHealthTracker;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickLog;
import com.github.oinsio.gnomish.app.serve.FeedAutomaton;
import com.github.oinsio.gnomish.app.serve.ForwardingDirtyNotifier;
import com.github.oinsio.gnomish.app.serve.ForwardingRemoteOutageLedgerSink;
import com.github.oinsio.gnomish.app.serve.RemoteOutageGate;
import com.github.oinsio.gnomish.app.serve.RemoteOutageGates;
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass;
import com.github.oinsio.gnomish.app.serve.SandboxLifecycleTick;
import com.github.oinsio.gnomish.app.serve.ServeShutdown;
import com.github.oinsio.gnomish.app.serve.SlotLedger;
import com.github.oinsio.gnomish.app.serve.TakeSlotRunner;
import com.github.oinsio.gnomish.app.serve.WorktreeJanitor;
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import com.github.oinsio.gnomish.serveobservability.SweepVital;
import java.time.Clock;

/**
 * Orchestrates the whole {@code serve} daemon runtime once the tracker is live, over the leaf
 * builders {@link ServeAssembly} holds. An assembly object (design D7 of collapse-composition-roots):
 * built once by the composition root with the command's fixed equipment and held by {@link
 * ServeCommand}, whose {@link #assemble} takes only the per-invocation job. It carries no decision
 * of its own, so it has no spec of its own; {@code ServeRuntimeWiringSpec} and {@code
 * ServeShutdownWiringSpec} drive it end to end.
 *
 * <p>Implements FR13 of add-factory-serve. Implements FR1, FR4, FR7, FR8, FR9, FR12, D12 of
 * add-serve-observability. Implements FR8 of collapse-composition-roots.
 */
final class ServeRuntimeAssembly {

    private final SlotWiringFactory slotWiringFactory;
    private final ServeAssembly builders;
    private final TaskGit git;
    private final FactoryPaths paths;
    private final Clock clock;
    private final SandboxLifecyclePass sandboxLifecyclePass;
    private final SandboxProperties sandboxProperties;

    /**
     * @param slotWiringFactory builds the daemon's one slot wiring, the equipment {@code serve}
     *     shares with {@code take} (design D9 of collapse-composition-roots)
     * @param builders the leaf builders over the daemon's properties and engine clock
     * @param git the task-git port, decorated here with the remote-outage gate
     * @param paths the worktrees root the janitor sweeps and the home the observability files live in
     * @param clock supplies "now" for the sweep tick log and the observability wiring
     * @param sandboxLifecyclePass the sweep-lifecycle evaluation seam; {@link SandboxLifecyclePass#NONE}
     *     on a host-only install
     * @param sandboxProperties supplies the sandbox reap age the sweep vital is measured against —
     *     the installation's one settings bean, the same the sweep-lifecycle pass is built from
     */
    ServeRuntimeAssembly(
            SlotWiringFactory slotWiringFactory,
            ServeAssembly builders,
            TaskGit git,
            FactoryPaths paths,
            Clock clock,
            SandboxLifecyclePass sandboxLifecyclePass,
            SandboxProperties sandboxProperties) {
        this.slotWiringFactory = slotWiringFactory;
        this.builders = builders;
        this.git = git;
        this.paths = paths;
        this.clock = clock;
        this.sandboxLifecyclePass = sandboxLifecyclePass;
        this.sandboxProperties = sandboxProperties;
    }

    /**
     * Wraps the bound tracker in a {@link TrackerHealthTracker} (FR8, D12) and derives the bound
     * tracker every downstream caller works with over it (design D8, amendment b, of
     * collapse-composition-roots), builds the one {@link TakeHeartbeat} whose {@code ClaimBeat}/{@code
     * ClaimLossFlag} every slot shares (FR13), then the {@link SlotLedger}, {@link TakeSlotRunner},
     * {@link FeedAutomaton}, {@link ServeShutdown}, {@link WorktreeJanitor} and the {@link
     * ObservabilityWiring}, and attaches the ledger writer to the slot runner.
     */
    ServeRuntime assemble(ServeArguments serveArguments, BoundTracker bound, int effectiveSlots) {
        // FR8, D12: shared by every downstream caller (heartbeat, slot runner, feed automaton) — the
        // raw tracker reaches nothing below this line.
        TrackerHealthTracker trackerHealth = builders.trackerHealth(bound.tracker());
        BoundTracker served = bound.withTracker(trackerHealth);

        // FR1: stand-in bound to SnapshotWriter::markDirty by the writer ObservabilityAssembly
        // builds; built before the heartbeat so its state trigger (FR7) wakes it too.
        ForwardingDirtyNotifier dirtyNotifier = new ForwardingDirtyNotifier();

        // FR13: joins the assembly before TakeSlotRunner is built (reused for the daemon's lifetime).
        // FR7 (design D4): the heartbeat's state transitions wake the same writer.
        TakeHeartbeat heartbeat = TakeHeartbeat.forRun(
                served.tracker(), served.trackerConfig(), new ThreadSleeper(), dirtyNotifier::markDirty);

        SlotLedger slotLedger = builders.slotLedger(effectiveSlots, dirtyNotifier);
        // FR14, NFR-R3 of add-base-ref-resolution (task 7.3): ONE gate instance shared by the slot
        // runner (which opens it on an InfrastructureUnavailable result) and the feed automaton
        // (which consults it before every claim) — a fresh daemon starts closed (FR14).
        // FR14, NFR-O1, NFR-O3 of add-base-ref-resolution (task 7.4): the gate is built before
        // ObservabilityAssembly constructs the ledger appender its remoteOutage line needs, so the
        // ledger sink is a forwarding stand-in — same construction-order cycle ForwardingDirtyNotifier
        // already breaks for the snapshot writer, bound below once ObservabilityAssembly returns.
        ForwardingRemoteOutageLedgerSink remoteOutageLedgerSink = new ForwardingRemoteOutageLedgerSink();
        RemoteOutageGate remoteOutageGate = builders.remoteOutageGate(
                git.baseRefs(), serveArguments.dir(), dirtyNotifier::markDirty, remoteOutageLedgerSink);
        // The serve side's one slot wiring (design "Where a SlotWiring is built" of
        // introduce-slot-wiring; built by the factory of design D9 of collapse-composition-roots):
        // built once per daemon, as soon as the heartbeat and the gate exist, and shared by every
        // slot through the one slot runner. Its git is the gate-signaling one (D6): the decoration
        // is this root's decision, not the slot's.
        var wiring =
                slotWiringFactory.slotWiring(served, RemoteOutageGates.signaling(git, remoteOutageGate), heartbeat);
        TakeSlotRunner slotRunner = ServeAssembly.slotRunner(
                serveArguments, served.definition(), served.tracker(), served.instanceId(), wiring);
        FeedAutomaton automaton = builders.feedAutomaton(
                served.trackerConfig(),
                served.tracker(),
                served.instanceId(),
                slotLedger,
                slotRunner,
                dirtyNotifier,
                remoteOutageGate);
        ServeShutdown shutdown = builders.shutdown(slotLedger, heartbeat.flag(), heartbeat.standingReaper());
        WorktreeJanitor worktreeJanitor =
                builders.worktreeJanitor(serveArguments, paths.worktreesRoot(), slotLedger, git);
        // NFR-O1 of add-serve-sandbox-lifecycle: built before the observability wiring, which reads
        // it for `vitals.sweep`, and before the tick, which writes it — the log, not the tick
        // thread, is what the two share, so neither construction waits on the other. The reap
        // threshold every kept environment's remaining margin is measured against comes from the
        // SAME SandboxProperties the sweep policy itself was built from, so the dashboard's
        // time-to-reap can never disagree with the reaper's own decision.
        SweepTickLog sweepTickLog =
                new SweepTickLog(sandboxProperties.keptReapAge(), clock, SweepVital.MAX_KEPT_INVENTORY);
        // FR1, FR4, FR7, FR9, FR12 of add-serve-observability (task 5.1, task 2.5).
        ObservabilityWiring observability = builders.observability(
                served.instanceId(),
                paths.homeDir(),
                dirtyNotifier,
                clock,
                new SnapshotSources(
                        automaton,
                        slotLedger,
                        effectiveSlots,
                        heartbeat.progress(),
                        trackerHealth,
                        (InstanceHeartbeat) heartbeat.instance(),
                        heartbeat.standingReaper(),
                        worktreeJanitor,
                        sweepTickLog,
                        remoteOutageGate));
        // NFR-O1, NFR-O3 of add-base-ref-resolution: only now, with the ledger appender built, can
        // the gate's stand-in ledger sink be rebound to the real remoteOutage write point.
        remoteOutageLedgerSink.bind(observability.remoteOutageLedgerWriter());
        SandboxLifecycleTick sandboxLifecycleTick = builders.sandboxLifecycleTick(
                serveArguments,
                sandboxLifecyclePass,
                heartbeat.livenessOracle(),
                sweepTickLog,
                observability.sweepLedgerWriter(),
                observability.sweepLedgerWriter());
        slotRunner.attachLedgerWriter(observability.taskOutcomeLedgerWriter());
        return new ServeRuntime(
                automaton,
                slotRunner,
                shutdown,
                worktreeJanitor,
                heartbeat.standingReaper(),
                observability,
                sandboxLifecycleTick);
    }
}
