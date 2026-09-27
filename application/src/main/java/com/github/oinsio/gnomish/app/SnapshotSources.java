package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.lease.HeartbeatProgress;
import com.github.oinsio.gnomish.app.lease.InstanceHeartbeat;
import com.github.oinsio.gnomish.app.lease.StandingReaper;
import com.github.oinsio.gnomish.app.port.tracker.TrackerHealthTracker;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickLog;
import com.github.oinsio.gnomish.app.serve.FeedAutomaton;
import com.github.oinsio.gnomish.app.serve.LifecycleStateTracker;
import com.github.oinsio.gnomish.app.serve.RemoteOutageGate;
import com.github.oinsio.gnomish.app.serve.SlotLedger;
import com.github.oinsio.gnomish.app.serve.WorktreeJanitor;
import com.github.oinsio.gnomish.serveobservability.FeedSnapshotAssembler;
import com.github.oinsio.gnomish.serveobservability.InstanceInfo;
import com.github.oinsio.gnomish.serveobservability.LifecycleSnapshotAssembler;
import com.github.oinsio.gnomish.serveobservability.RemoteHealthAssembler;
import com.github.oinsio.gnomish.serveobservability.SlotEntryAssembler;
import com.github.oinsio.gnomish.serveobservability.SlotsSnapshot;
import com.github.oinsio.gnomish.serveobservability.Snapshot;
import com.github.oinsio.gnomish.serveobservability.TrackerHealthAssembler;
import com.github.oinsio.gnomish.serveobservability.VitalsSnapshotAssembler;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The live collaborators one {@code serve} daemon's status snapshot is read from, and the reading
 * itself (design D2 of collapse-composition-roots): every section of the snapshot but the
 * lifecycle one is assembled from these on each write, so they arrive together and are read
 * together by {@link #snapshot}. Built once by {@link ServeRuntimeAssembly}, which owns every one
 * of them; {@link ObservabilityAssembly} only closes the snapshot writer over it.
 *
 * <p>Implements FR1, FR4 of collapse-composition-roots; FR1, FR7 of add-serve-observability.
 *
 * @param automaton the feed automaton behind the {@code feed} section; never null
 * @param slotLedger the shared slot registry behind the {@code slots} entries; never null
 * @param slotCapacity the configured slot count N — the snapshot's {@code slots.capacity}
 * @param progress the shared durable-progress hook enriching slot entries (FR6, D11 of
 *     add-serve-observability); never null
 * @param trackerHealth the shared tracker-port health decorator behind the {@code trackerHealth}
 *     section (FR8, D12 of add-serve-observability); never null
 * @param heartbeat the shared instance heartbeat behind {@code vitals.heartbeat} (FR7); never null
 * @param standingReaper the standing reaper behind {@code vitals.reaper} (FR7); never null
 * @param worktreeJanitor the worktree janitor behind {@code vitals.janitor} (FR7); never null
 * @param sweepTickLog the sandbox-lifecycle sweep's per-tick record behind {@code vitals.sweep}
 *     (NFR-O1 of add-serve-sandbox-lifecycle); never null
 * @param remoteOutageGate the shared remote outage gate behind the {@code remote} section (NFR-O3,
 *     UX6 of add-base-ref-resolution); never null
 */
record SnapshotSources(
        FeedAutomaton automaton,
        SlotLedger slotLedger,
        int slotCapacity,
        HeartbeatProgress progress,
        TrackerHealthTracker trackerHealth,
        InstanceHeartbeat heartbeat,
        StandingReaper standingReaper,
        WorktreeJanitor worktreeJanitor,
        SweepTickLog sweepTickLog,
        RemoteOutageGate remoteOutageGate) {

    /**
     * Reads the current state of every source into one snapshot.
     *
     * @param instance this process's identity, carried in the snapshot; never null
     * @param lifecycleTracker the daemon's lifecycle state, which the snapshot writer's own
     *     assembly creates and therefore cannot be a source built before it; never null
     * @param startedAt the daemon's start instant, a placeholder the writer overwrites on every
     *     actual write; never null
     * @param sweepInterval the configured sandbox sweep interval {@code vitals.sweep} is measured
     *     against; never null
     * @return the assembled snapshot; never null
     */
    Snapshot snapshot(
            InstanceInfo instance, LifecycleStateTracker lifecycleTracker, Instant startedAt, Duration sweepInterval) {
        return new Snapshot(
                1,
                startedAt, // overwritten by SnapshotWriter#withSelfDescription on every actual write
                0,
                instance,
                LifecycleSnapshotAssembler.assemble(lifecycleTracker),
                FeedSnapshotAssembler.assemble(automaton),
                new SlotsSnapshot(slotCapacity, SlotEntryAssembler.assemble(slotLedger, progress)),
                VitalsSnapshotAssembler.assemble(
                        heartbeat, standingReaper, worktreeJanitor, sweepTickLog, sweepInterval),
                TrackerHealthAssembler.assemble(trackerHealth),
                RemoteHealthAssembler.assemble(List.of(remoteOutageGate)));
    }
}
