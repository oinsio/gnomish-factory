package com.github.oinsio.gnomish.serveobservability.writer;

import com.github.oinsio.gnomish.atomicfile.AtomicFileWriter;
import com.github.oinsio.gnomish.serveobservability.Snapshot;
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The write half of the snapshot writer (design D4 of add-serve-observability), extracted from
 * {@link SnapshotWriter} so that class owns only the thread lifecycle and dirty-flag coalescing
 * while this one owns the single action taken on every wake: serialize the current {@link
 * Snapshot} to the target file, then run the ledger retention sweep.
 *
 * <p>Self-description ({@code writtenAt}, {@code intervalSeconds}) is stamped here on the writer's
 * own {@link Clock}, right before serialization — not by the supplier — so callers assembling
 * {@link Snapshot} content never set these fields; whatever the supplier carries is replaced (FR2).
 *
 * <p><b>Failure isolation (NFR-R1).</b> {@link #writeOnce()} and the retention sweep each have
 * their own try/catch, so a failing supplier/serialize/atomic-write never skips the sweep and a
 * failing sweep never masks the write; neither can crash the writer thread.
 *
 * <p>Implements FR1, FR2, FR15, NFR-R1 of add-serve-observability.
 */
final class SnapshotWriteCycle {

    private static final Logger log = LoggerFactory.getLogger(SnapshotWriteCycle.class);

    private final Path targetFile;
    private final Supplier<Snapshot> snapshotSupplier;
    private final SnapshotJsonMapper jsonMapper;
    private final Duration interval;
    private final Clock clock;
    private final LedgerRetentionSweeper retentionSweeper;

    SnapshotWriteCycle(
            Path targetFile,
            Supplier<Snapshot> snapshotSupplier,
            SnapshotJsonMapper jsonMapper,
            Duration interval,
            Clock clock,
            LedgerRetentionSweeper retentionSweeper) {
        this.targetFile = targetFile;
        this.snapshotSupplier = snapshotSupplier;
        this.jsonMapper = jsonMapper;
        this.interval = interval;
        this.clock = clock;
        this.retentionSweeper = retentionSweeper;
    }

    // One wake's worth of work: write, then sweep. Package-private so write-content specs call it
    // directly, with no thread and no waiting.
    void run() {
        writeOnce();
        sweepLedgerRetention();
    }

    // NFR-R1: one failure domain from snapshot to disk (supplier, serialize, atomic write can each
    // fail independently) — logged and skipped, never crashes the writer thread. The final
    // shutdown write (SnapshotWriter#stopAfterFinalWrite) calls this alone, without the sweep.
    void writeOnce() {
        try {
            Snapshot snapshot = snapshotSupplier.get().withSelfDescription(clock.instant(), interval.toSeconds());
            String json = jsonMapper.serialize(snapshot);
            AtomicFileWriter.write(targetFile, json);
        } catch (IOException | RuntimeException e) {
            log.warn("snapshot writer: failed to write {}", targetFile, e);
        }
    }

    // FR15, design D7: its own try/catch so a sweep failure is isolated from the write (NFR-R1).
    //
    // task 6.3 documented exception: LedgerRetentionSweeper#sweep already catches and swallows
    // every IOException its own filesystem operations can produce (listing, delete — see
    // LedgerRetentionSweeperSpec), so under the current implementation this catch cannot itself
    // be reached by any test that exercises a real (not artificially broken) sweeper; kept as
    // defense in depth.
    private void sweepLedgerRetention() {
        try {
            retentionSweeper.sweep();
        } catch (RuntimeException e) {
            log.warn("snapshot writer: ledger retention sweep failed", e);
        }
    }
}
