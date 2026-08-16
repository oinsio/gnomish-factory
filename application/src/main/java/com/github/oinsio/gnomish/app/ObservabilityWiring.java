package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.serve.DaemonLifecycleState;
import com.github.oinsio.gnomish.app.serve.LifecycleStateTracker;
import com.github.oinsio.gnomish.serveobservability.InstanceInfo;
import com.github.oinsio.gnomish.serveobservability.writer.LifecycleLedgerWriter;
import com.github.oinsio.gnomish.serveobservability.writer.RotatingLedgerAppender;
import com.github.oinsio.gnomish.serveobservability.writer.RunSummaryLedgerWriter;
import com.github.oinsio.gnomish.serveobservability.writer.SnapshotWriter;
import com.github.oinsio.gnomish.serveobservability.writer.TaskOutcomeLedgerWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The daemon-lifetime observability handle {@link ServeCommand}/{@link ServeShutdownWiring} drive
 * (task 5.1, FR1, FR4, FR12): bundles everything {@link ObservabilityAssembly#assemble} built —
 * the {@link SnapshotWriter}, the {@link LifecycleStateTracker} both the writer's supplier and
 * the ledger's {@code lifecycle} lines read from, and the ledger writers — behind a small, ordered
 * set of lifecycle methods, so neither caller needs to know the construction-order dance {@link
 * ObservabilityAssembly} performed to get here.
 *
 * <p>{@link #finalizeStopped} is idempotent (guarded by {@link #stopped}): both the drain-complete
 * path and the SIGTERM shutdown hook can reach it — the JVM runs its shutdown hook on ANY exit,
 * including the ordinary post-drain one (see {@link ServeShutdown}'s own Javadoc) — and a second
 * call after the first must be a no-op, not a second {@code stopped} ledger line or a redundant
 * final write.
 *
 * <p>Implements FR1, FR4, FR12 of add-serve-observability.
 */
final class ObservabilityWiring {

    private final LifecycleStateTracker lifecycleTracker;
    private final SnapshotWriter snapshotWriter;
    private final LifecycleLedgerWriter lifecycleLedgerWriter;
    private final TaskOutcomeLedgerWriter taskOutcomeLedgerWriter;
    private final RotatingLedgerAppender ledgerAppender;
    private final InstanceInfo instance;
    private final Clock clock;
    private final AtomicBoolean stopped = new AtomicBoolean();

    ObservabilityWiring(
            LifecycleStateTracker lifecycleTracker,
            SnapshotWriter snapshotWriter,
            LifecycleLedgerWriter lifecycleLedgerWriter,
            TaskOutcomeLedgerWriter taskOutcomeLedgerWriter,
            RotatingLedgerAppender ledgerAppender,
            InstanceInfo instance,
            Clock clock) {
        this.lifecycleTracker = lifecycleTracker;
        this.snapshotWriter = snapshotWriter;
        this.lifecycleLedgerWriter = lifecycleLedgerWriter;
        this.taskOutcomeLedgerWriter = taskOutcomeLedgerWriter;
        this.ledgerAppender = ledgerAppender;
        this.instance = instance;
        this.clock = clock;
    }

    /** The {@code taskOutcome} write point every slot attaches to (FR11); never null. */
    TaskOutcomeLedgerWriter taskOutcomeLedgerWriter() {
        return taskOutcomeLedgerWriter;
    }

    /** Starts the snapshot writer thread and records the {@code started} ledger line (FR1, FR12). */
    void start() {
        snapshotWriter.start();
        lifecycleLedgerWriter.writeStarted();
    }

    /** Moves the daemon to {@code draining}: finishing in-flight tasks, claiming no new ones (FR4). */
    void beginDraining() {
        lifecycleTracker.transitionTo(DaemonLifecycleState.DRAINING, clock.instant());
    }

    /** Moves the daemon to {@code stopping}: shutdown/cleanup in progress (FR4). */
    void beginStopping() {
        lifecycleTracker.transitionTo(DaemonLifecycleState.STOPPING, clock.instant());
    }

    /**
     * The terminal step (FR4, FR12): moves to {@code stopped(reason)}, appends the ledger's {@code
     * stopped} line, then forces one last synchronous snapshot write reflecting that state before
     * stopping the writer thread ({@link SnapshotWriter#stopAfterFinalWrite()}) — so the file left
     * behind after this call always shows {@code stopped}, never a stale earlier state. A no-op on
     * every call after the first (see class Javadoc).
     *
     * @param reason why the daemon stopped (e.g. {@code "sigterm"}, {@code "drainComplete"})
     */
    void finalizeStopped(String reason) {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        lifecycleTracker.stop(reason, clock.instant());
        lifecycleLedgerWriter.writeStopped(reason);
        snapshotWriter.stopAfterFinalWrite();
    }

    /** A fresh drain-run {@code runSummary} writer over this instance's shared ledger appender (FR13). */
    RunSummaryLedgerWriter newRunSummaryLedgerWriter() {
        return new RunSummaryLedgerWriter(ledgerAppender, instance, clock);
    }

    /** The current instant on this wiring's clock — the drain run's {@code startedAt} (FR13). */
    Instant now() {
        return clock.instant();
    }
}
