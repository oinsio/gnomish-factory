package com.github.oinsio.gnomish.serveobservability.writer;

import com.github.oinsio.gnomish.serveobservability.Snapshot;
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single snapshot writer thread (design D4 of add-serve-observability): a dedicated virtual
 * thread that wakes either on the configured timer beat or on an immediate {@link #markDirty()}
 * trigger, and on every wake runs exactly one {@link SnapshotWriteCycle} — the extracted write
 * half (serialize + atomic overwrite + retention sweep), so this class owns only the thread
 * lifecycle and dirty-flag coalescing. Two trigger points (timer, dirty flag), one write point
 * (FR1): no other thread ever writes the target file, so {@link AtomicFileWriter}'s "reader never
 * sees a partial file" guarantee is never raced by a second concurrent writer.
 *
 * <p>Rapid {@link #markDirty()} calls coalesce: {@code dirty} is a single boolean, not a counter
 * or queue, so any number of triggers landing while the writer is asleep or mid-write produce at
 * most one more write after the current wake finishes (design D4 Risks).
 *
 * <p>Implements FR1, FR2, FR15, NFR-R1 of add-serve-observability.
 */
public final class SnapshotWriter {

    private static final Logger log = LoggerFactory.getLogger(SnapshotWriter.class);

    private final SnapshotWriteCycle writeCycle;
    private final Duration interval;
    private final Object lock = new Object();
    private boolean dirty;
    private volatile boolean running;
    private @Nullable Thread worker;

    /**
     * @param targetFile the snapshot file this thread exclusively writes; never null
     * @param snapshotSupplier produces the current snapshot content on every wake; called on the
     *     writer thread only; its self-description fields are overwritten before serialization
     * @param jsonMapper serializes the snapshot to its JSON contract; never null
     * @param interval the maximum gap between writes absent a dirty-flag trigger ({@code
     *     factory.serve.snapshot-interval}); must be positive; also the stamped {@code
     *     intervalSeconds} value (FR2)
     * @param clock supplies the actual wall-clock time stamped as {@code writtenAt}, taken at
     *     write time, not at supplier-call time; never null
     * @param ledgerRetentionDays days a ledger file is kept before the sweep (FR15, design D7)
     *     deletes it, scanning {@code targetFile}'s parent directory; {@code 0} disables the sweep
     */
    public SnapshotWriter(
            Path targetFile,
            Supplier<Snapshot> snapshotSupplier,
            SnapshotJsonMapper jsonMapper,
            Duration interval,
            Clock clock,
            int ledgerRetentionDays) {
        this.interval = interval;
        Path directory = Objects.requireNonNull(targetFile.getParent(), "targetFile must have a parent directory");
        LedgerRetentionSweeper retentionSweeper = new LedgerRetentionSweeper(directory, ledgerRetentionDays, clock);
        this.writeCycle =
                new SnapshotWriteCycle(targetFile, snapshotSupplier, jsonMapper, interval, clock, retentionSweeper);
    }

    /** Starts the writer thread: an immediate first write, then timer/dirty-flag wakes. */
    public void start() {
        running = true;
        worker = Thread.ofVirtual().name("gnomish-snapshot-writer").start(this::loop);
    }

    /** Stops the writer thread after its current or next wake completes, waking it immediately. */
    public void stop() {
        running = false;
        wake();
    }

    /**
     * Marks the current content dirty and wakes the writer immediately (FR1). Safe to call from
     * any thread; rapid calls coalesce into at most one extra write (design D4 Risks).
     */
    public void markDirty() {
        wake();
    }

    /**
     * Stops the writer thread, guaranteeing the LAST bytes written to {@code targetFile} reflect
     * the snapshot content at the moment this is called (FR4's final {@code stopped} snapshot).
     * Unlike {@link #markDirty()} + {@link #stop()} — which races the background thread — this
     * lets it fully exit first ({@link #stop()} then {@link Thread#join()}), then performs one
     * last synchronous write alone. The caller must have already updated whatever state the
     * supplier reads before calling this.
     *
     * @throws IllegalStateException if the writer was never {@link #start()}ed
     */
    public void stopAfterFinalWrite() {
        Thread w = worker;
        if (w == null) {
            throw new IllegalStateException("SnapshotWriter was never started");
        }
        stop();
        try {
            w.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        writeCycle.writeOnce();
    }

    private void wake() {
        synchronized (lock) {
            dirty = true;
            lock.notifyAll();
        }
    }

    // Package-private: lifecycle specs drive the real thread; write-content specs drive tick().
    //
    // task 6.3 documented exception: this catch is a second, outer safety net around tick() —
    // writeSnapshot() and sweepLedgerRetention() below already catch and swallow every
    // IOException/RuntimeException each of their own operations can produce (each has its own
    // try/catch, NFR-R1), so under the current implementation tick() cannot itself let a
    // RuntimeException escape; this line is therefore structurally unreachable by any test that
    // exercises real (not artificially broken) sub-methods. Kept as defense in depth so the
    // background thread survives even a future bug in tick()'s own exception handling, rather
    // than dying silently and going unnoticed until the snapshot file goes stale.
    void loop() {
        while (running) {
            try {
                tick();
            } catch (RuntimeException e) {
                log.warn("snapshot writer: tick failed; will retry on the next wake", e);
            }
            awaitNextWake();
        }
    }

    private void awaitNextWake() {
        synchronized (lock) {
            long deadlineNanos = System.nanoTime() + interval.toNanos();
            while (!dirty && running) {
                long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingMillis <= 0) {
                    break;
                }
                try {
                    lock.wait(remainingMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            dirty = false;
        }
    }

    // Package-private: write-content specs call this directly, with no thread and no waiting.
    void tick() {
        writeCycle.run();
    }

    // Package-private: specs join the worker to observe a deterministic stop.
    @Nullable
    Thread worker() {
        return worker;
    }
}
