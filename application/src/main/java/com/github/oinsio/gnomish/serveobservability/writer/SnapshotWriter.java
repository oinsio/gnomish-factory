package com.github.oinsio.gnomish.serveobservability.writer;

import com.github.oinsio.gnomish.app.daemon.LoopOrder;
import com.github.oinsio.gnomish.app.daemon.LoopShape;
import com.github.oinsio.gnomish.app.daemon.LoopWait;
import com.github.oinsio.gnomish.app.daemon.RestartPolicy;
import com.github.oinsio.gnomish.app.daemon.SupervisedLoop;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.serveobservability.Snapshot;
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper;
import com.github.oinsio.gnomish.status.DaemonComponent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The single snapshot writer (design D4 of add-serve-observability): it writes on the configured
 * timer beat or at once on a {@link #markDirty()} trigger, and every write is exactly one {@link
 * SnapshotWriteCycle} — the extracted write half (serialize + atomic overwrite + retention sweep).
 * Two trigger points (timer, dirty trigger), one write point (FR1): no other thread ever writes the
 * target file while the loop runs, so {@link com.github.oinsio.gnomish.atomicfile.AtomicFileWriter}'s
 * "reader never sees a partial file" guarantee is never raced by a second concurrent writer.
 *
 * <p><b>The thread is a supervised daemon loop</b> (design D1, D3, D7, D8 of
 * supervise-daemon-loops-and-embed-dashboard). This class owns only its write cycle; the thread,
 * the guard, the stop and the restart belong to the {@link SupervisedLoop} it holds: tick → wait on
 * a {@link LoopWait.IntervalOrSignal} of the configured interval, framed as {@link
 * DaemonComponent#SNAPSHOT}, under {@link RestartPolicy.Unbounded} with a 10-minute cap. A failed
 * write cycle is the loop's {@code DAEMON_LOOP_TICK_FAILED} with {@code component=snapshot}, a
 * stray interrupt is absorbed and waited out in full, and a dead thread is respawned — so the
 * snapshot keeps being written while the daemon runs.
 *
 * <p>Rapid {@link #markDirty()} calls coalesce: they are signals of the loop's wait, whose surplus
 * is drained after every wake, so any number of triggers landing while the writer waits or writes
 * produce at most one more write after the current one (design D4 Risks of add-serve-observability).
 *
 * <p>Implements FR1, FR2, FR15, NFR-R1 of add-serve-observability; FR6, FR7 of
 * supervise-daemon-loops-and-embed-dashboard.
 */
public final class SnapshotWriter {

    /** The longest wait before a respawn (design D7 of supervise-daemon-loops-and-embed-dashboard). */
    private static final Duration RESTART_BACKOFF_CAP = Duration.ofMinutes(10);

    private final SnapshotWriteCycle writeCycle;
    private final LoopWait.IntervalOrSignal wake;
    private final SupervisedLoop loop;
    private volatile boolean started;

    /**
     * @param targetFile the snapshot file this writer exclusively writes; never null
     * @param snapshotSupplier produces the current snapshot content on every write; called on the
     *     writer's thread (and once by the final write); its self-description fields are
     *     overwritten before serialization
     * @param jsonMapper serializes the snapshot to its JSON contract; never null
     * @param interval the maximum gap between writes absent a dirty-flag trigger ({@code
     *     factory.serve.snapshot-interval}); must be positive; also the stamped {@code
     *     intervalSeconds} value (FR2)
     * @param time the writer's one time equipment (virtual under test): its clock stamps {@code
     *     writtenAt} at write time, ages the ledger files for the retention sweep and times the
     *     loop's failure roll-ups; its sleeper waits the restart backoff (design D16, D20 of
     *     supervise-daemon-loops-and-embed-dashboard); never null
     * @param ledgerRetentionDays days a ledger file is kept before the sweep (FR15, design D7)
     *     deletes it, scanning {@code targetFile}'s parent directory; {@code 0} disables the sweep
     */
    public SnapshotWriter(
            Path targetFile,
            Supplier<Snapshot> snapshotSupplier,
            SnapshotJsonMapper jsonMapper,
            Duration interval,
            TimeEquipment time,
            int ledgerRetentionDays) {
        Path directory = Objects.requireNonNull(targetFile.getParent(), "targetFile must have a parent directory");
        LedgerRetentionSweeper retentionSweeper =
                new LedgerRetentionSweeper(directory, ledgerRetentionDays, time.clock());
        this.writeCycle = new SnapshotWriteCycle(
                targetFile, snapshotSupplier, jsonMapper, interval, time.clock(), retentionSweeper);
        this.wake = new LoopWait.IntervalOrSignal(interval);
        LoopShape shape = new LoopShape(
                DaemonComponent.SNAPSHOT,
                LoopOrder.TICK_THEN_WAIT,
                wake,
                new RestartPolicy.Unbounded(interval, RESTART_BACKOFF_CAP));
        this.loop = new SupervisedLoop(shape, this::tick, time);
    }

    /** Starts the writer: an immediate first write, then timer and dirty-flag wakes. Idempotent. */
    public void start() {
        started = true;
        loop.start();
    }

    /**
     * Stops the writer and returns at once: a wait in progress is cut short, a write in progress
     * completes, and no respawn follows (design D4 of supervise-daemon-loops-and-embed-dashboard).
     */
    public void stop() {
        loop.stop();
    }

    /**
     * Marks the current content dirty and wakes the writer immediately (FR1). Safe to call from
     * any thread; rapid calls coalesce into at most one extra write (design D4 Risks).
     */
    public void markDirty() {
        wake.signal();
    }

    /**
     * Stops the writer, guaranteeing the LAST bytes written to {@code targetFile} reflect the
     * snapshot content at the moment this is called (FR4 of add-serve-observability's final {@code
     * stopped} snapshot; FR7 of supervise-daemon-loops-and-embed-dashboard). The loop is stopped
     * and joined first — including a worker a death handler respawned meanwhile — and only then is
     * one last synchronous write performed alone. If the joining thread is interrupted, its flag is
     * restored and the final write still happens. The caller must have already updated whatever
     * state the supplier reads before calling this.
     *
     * @throws IllegalStateException if the writer was never {@link #start()}ed
     */
    public void stopAfterFinalWrite() {
        if (!started) {
            throw new IllegalStateException("SnapshotWriter was never started");
        }
        loop.stopAndJoin();
        writeCycle.writeOnce();
    }

    // Package-private: write-content specs call this directly, with no thread and no waiting.
    void tick() {
        writeCycle.run();
    }
}
