package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.app.lease.InstanceHeartbeat;
import com.github.oinsio.gnomish.app.lease.StandingReaper;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.serve.FeedAutomaton;
import com.github.oinsio.gnomish.app.serve.ForwardingDirtyNotifier;
import com.github.oinsio.gnomish.app.serve.LifecycleStateTracker;
import com.github.oinsio.gnomish.app.serve.SlotLedger;
import com.github.oinsio.gnomish.app.serve.WorktreeJanitor;
import com.github.oinsio.gnomish.serveobservability.InstanceInfo;
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths;
import com.github.oinsio.gnomish.serveobservability.VitalsSnapshotAssembler;
import com.github.oinsio.gnomish.serveobservability.json.LedgerJsonMapper;
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper;
import com.github.oinsio.gnomish.serveobservability.writer.LedgerAppender;
import com.github.oinsio.gnomish.serveobservability.writer.RotatingLedgerAppender;
import com.github.oinsio.gnomish.serveobservability.writer.SnapshotWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Clock;
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
     * @param dirtyNotifier the caller's {@link ForwardingDirtyNotifier}, already handed to the
     *     slot ledger and the feed automaton at their own construction; {@link
     *     ForwardingDirtyNotifier#bind} is called here once the real writer exists
     * @param clock the wall-clock time source for every write point; never null
     * @param sources the live collaborators every snapshot is read from (design D2 of
     *     collapse-composition-roots); its slot ledger also backs the task-outcome ledger writer;
     *     never null
     * @return the daemon-lifetime observability handle; never null
     */
    static ObservabilityWiring assemble(
            FactoryProperties factoryProperties,
            ServeProperties serveProperties,
            InstanceId instanceId,
            Path homeDir,
            ForwardingDirtyNotifier dirtyNotifier,
            Clock clock,
            SnapshotSources sources) {
        String instanceName = factoryProperties.instanceName();
        InstanceInfo instance = new InstanceInfo(instanceId.value(), resolveHost(), resolveFactoryVersion());
        Instant startedAt = clock.instant();
        LifecycleStateTracker lifecycleTracker = new LifecycleStateTracker(startedAt, dirtyNotifier);

        SnapshotWriter writer = new SnapshotWriter(
                ObservabilityPaths.snapshotFile(homeDir, instanceName),
                () -> sources.snapshot(instance, lifecycleTracker, startedAt, serveProperties.sandboxSweepInterval()),
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
        // NFR-O2 of add-serve-sandbox-lifecycle; NFR-O1, NFR-O3 of add-base-ref-resolution: every
        // write point shares this instance's appender, so each line rotates and is retained exactly
        // like every other ledger line.
        LedgerWriters ledgerWriters = new LedgerWriters(ledgerAppender, sources.slotLedger(), instance, clock);

        return new ObservabilityWiring(lifecycleTracker, writer, ledgerWriters, clock);
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
