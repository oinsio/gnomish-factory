package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.app.lease.HeartbeatProgress;
import com.github.oinsio.gnomish.app.lease.InstanceHeartbeat;
import com.github.oinsio.gnomish.app.lease.StandingReaper;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TrackerHealthTracker;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickLog;
import com.github.oinsio.gnomish.app.serve.FeedAutomaton;
import com.github.oinsio.gnomish.app.serve.ForwardingDirtyNotifier;
import com.github.oinsio.gnomish.app.serve.LifecycleStateTracker;
import com.github.oinsio.gnomish.app.serve.SlotLedger;
import com.github.oinsio.gnomish.app.serve.WorktreeJanitor;
import com.github.oinsio.gnomish.serveobservability.FeedSnapshotAssembler;
import com.github.oinsio.gnomish.serveobservability.InstanceInfo;
import com.github.oinsio.gnomish.serveobservability.LifecycleSnapshotAssembler;
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths;
import com.github.oinsio.gnomish.serveobservability.SlotEntryAssembler;
import com.github.oinsio.gnomish.serveobservability.SlotsSnapshot;
import com.github.oinsio.gnomish.serveobservability.Snapshot;
import com.github.oinsio.gnomish.serveobservability.TrackerHealthAssembler;
import com.github.oinsio.gnomish.serveobservability.VitalsSnapshotAssembler;
import com.github.oinsio.gnomish.serveobservability.json.LedgerJsonMapper;
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper;
import com.github.oinsio.gnomish.serveobservability.writer.LedgerAppender;
import com.github.oinsio.gnomish.serveobservability.writer.LifecycleLedgerWriter;
import com.github.oinsio.gnomish.serveobservability.writer.RotatingLedgerAppender;
import com.github.oinsio.gnomish.serveobservability.writer.SnapshotWriter;
import com.github.oinsio.gnomish.serveobservability.writer.SweepLedgerWriter;
import com.github.oinsio.gnomish.serveobservability.writer.TaskOutcomeLedgerWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Builds the observability writer + appender + ledger writers {@link ServeCommand} starts beside
 * {@code WorktreeJanitor} and stops in {@link ServeShutdownWiring} (task 5.1). Extracted purely to
 * keep {@link ServeCommand} within the file-size limit (process-invariants.md) — mirrors {@link
 * ServeAssembly}'s role for the add-factory-serve collaborators.
 *
 * <p><b>Construction-order cycle.</b> {@link SlotLedger}, {@link FeedAutomaton}, and {@link
 * LifecycleStateTracker} each need a {@code DirtyNotifier} at construction time (design D4), but
 * the real one — {@code SnapshotWriter::markDirty} — can only exist once the {@code
 * Supplier<Snapshot>} closing over those SAME objects has been built, which needs them to already
 * exist. {@link ForwardingDirtyNotifier} breaks the cycle: it is constructed first by the caller,
 * handed to those three state holders, and {@link ForwardingDirtyNotifier#bind} is called here
 * with the real writer once it exists, before the writer starts.
 *
 * <p><b>Vitals (task 2.5).</b> The snapshot's {@code vitals} section is assembled by {@link
 * VitalsSnapshotAssembler} straight from the three thread-owning collaborators {@link
 * ServeCommand} already builds: the shared {@link InstanceHeartbeat}, the {@link StandingReaper},
 * and the {@link WorktreeJanitor} — no placeholder remains.
 *
 * <p>Implements FR1, FR4, FR7, FR9, FR12 of add-serve-observability.
 */
final class ObservabilityAssembly {

    private ObservabilityAssembly() {}

    /**
     * Assembles the observability wiring for one {@code serve} invocation.
     *
     * @param factoryProperties supplies the instance name the observability directory is keyed by
     *     (FR9, design D2); never null
     * @param serveProperties supplies the snapshot interval and ledger retention (design D10);
     *     never null
     * @param instanceId this process's full instance id, carried in the snapshot/ledger data only,
     *     never the path (FR9); never null
     * @param homeDir the user's home directory the observability files live under (FR9, design
     *     D2); injected (not read inline) so tests can substitute a temp directory; never null
     * @param dirtyNotifier the caller's {@link ForwardingDirtyNotifier}, already handed to {@code
     *     slotLedger}/{@code automaton} at their own construction; {@link
     *     ForwardingDirtyNotifier#bind} is called here once the real writer exists
     * @param slotLedger the shared slot registry; never null
     * @param slotCapacity the configured slot count N — the snapshot's {@code slots.capacity}
     * @param automaton the assembled feed automaton whose {@link FeedAutomaton#view()} feeds the
     *     snapshot's {@code feed} section; never null
     * @param trackerHealth the shared tracker-port health decorator (FR8, D12); never null
     * @param progress the shared durable-progress hook enriching slot entries (FR6, D11); never
     *     null
     * @param heartbeat the shared instance heartbeat feeding {@code vitals.heartbeat} (FR7); never
     *     null
     * @param standingReaper the standing reaper feeding {@code vitals.reaper} (FR7); never null
     * @param worktreeJanitor the worktree janitor feeding {@code vitals.janitor} (FR7); never null
     * @param sweepTickLog the sandbox-lifecycle sweep's per-tick record feeding {@code
     *     vitals.sweep} (NFR-O1 of add-serve-sandbox-lifecycle); never null
     * @param clock the wall-clock time source for every write point; never null
     * @return the daemon-lifetime observability handle; never null
     */
    static ObservabilityWiring assemble(
            FactoryProperties factoryProperties,
            ServeProperties serveProperties,
            InstanceId instanceId,
            Path homeDir,
            ForwardingDirtyNotifier dirtyNotifier,
            SlotLedger slotLedger,
            int slotCapacity,
            FeedAutomaton automaton,
            TrackerHealthTracker trackerHealth,
            HeartbeatProgress progress,
            InstanceHeartbeat heartbeat,
            StandingReaper standingReaper,
            WorktreeJanitor worktreeJanitor,
            SweepTickLog sweepTickLog,
            Clock clock) {
        String instanceName = factoryProperties.instanceName();
        InstanceInfo instance = new InstanceInfo(instanceId.value(), resolveHost(), resolveFactoryVersion());
        Instant startedAt = clock.instant();
        LifecycleStateTracker lifecycleTracker = new LifecycleStateTracker(startedAt, dirtyNotifier);

        SnapshotWriter writer = new SnapshotWriter(
                ObservabilityPaths.snapshotFile(homeDir, instanceName),
                () -> assembleSnapshot(
                        instance,
                        lifecycleTracker,
                        automaton,
                        slotLedger,
                        slotCapacity,
                        progress,
                        trackerHealth,
                        heartbeat,
                        standingReaper,
                        worktreeJanitor,
                        sweepTickLog,
                        serveProperties.sandboxSweepInterval(),
                        startedAt),
                new SnapshotJsonMapper(),
                serveProperties.snapshotInterval(),
                clock,
                serveProperties.ledgerRetentionDays());
        // Breaks the construction-order cycle documented in the class Javadoc: only now, with the
        // writer built, can the state holders' stand-in notifier be rebound to the real one.
        dirtyNotifier.bind(writer::markDirty);

        Path initialLedgerFile =
                ObservabilityPaths.ledgerFile(homeDir, instanceName, LocalDate.ofInstant(startedAt, ZoneOffset.UTC));
        RotatingLedgerAppender ledgerAppender = new RotatingLedgerAppender(
                new LedgerAppender(initialLedgerFile, new LedgerJsonMapper()), homeDir, instanceName, clock);
        LifecycleLedgerWriter lifecycleLedgerWriter = new LifecycleLedgerWriter(ledgerAppender, instance, clock);
        TaskOutcomeLedgerWriter taskOutcomeLedgerWriter =
                new TaskOutcomeLedgerWriter(slotLedger, ledgerAppender, instance, clock);
        // NFR-O2 of add-serve-sandbox-lifecycle: the sweep's own lines share this instance's
        // appender, so they rotate and are retained exactly like every other ledger line.
        SweepLedgerWriter sweepLedgerWriter = new SweepLedgerWriter(ledgerAppender, instance, clock);

        return new ObservabilityWiring(
                lifecycleTracker,
                writer,
                lifecycleLedgerWriter,
                taskOutcomeLedgerWriter,
                sweepLedgerWriter,
                ledgerAppender,
                instance,
                clock);
    }

    private static Snapshot assembleSnapshot(
            InstanceInfo instance,
            LifecycleStateTracker lifecycleTracker,
            FeedAutomaton automaton,
            SlotLedger slotLedger,
            int slotCapacity,
            HeartbeatProgress progress,
            TrackerHealthTracker trackerHealth,
            InstanceHeartbeat heartbeat,
            StandingReaper standingReaper,
            WorktreeJanitor worktreeJanitor,
            SweepTickLog sweepTickLog,
            Duration sweepInterval,
            Instant startedAt) {
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
                TrackerHealthAssembler.assemble(trackerHealth));
    }

    // task 6.3 documented exception: the try branch (returning the real hostname) is exercised
    // directly by ObservabilityAssemblySpec ("the assembled instance carries the real resolved
    // host"), but the catch branch requires InetAddress.getLocalHost() to throw
    // UnknownHostException, which depends on host/network name resolution outside this process's
    // control (no PowerMock/mockito-inline in this stack to stub a JDK static method, per
    // ADR 0001) — no unit test can force it deterministically without faking the OS's own
    // hostname resolution. @DoNotMutate covers the whole method rather than leaving the reachable
    // branch's mutations dangling as unkillable-by-construction survivors.
    @com.github.oinsio.gnomish.DoNotMutate
    private static String resolveHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    private static String resolveFactoryVersion() {
        String version = ObservabilityAssembly.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }
}
