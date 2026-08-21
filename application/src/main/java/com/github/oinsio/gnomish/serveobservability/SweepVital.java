package com.github.oinsio.gnomish.serveobservability;

import java.time.Instant;
import java.util.List;

/**
 * The snapshot's {@code vitals.sweep} entry (NFR-O1 of add-serve-sandbox-lifecycle): when the
 * sandbox-lifecycle tick last completed, the per-category verdict counts of THAT tick alone (not
 * cumulative — an operator reading the file wants "what did the last pass decide", and history is
 * the ledger's job), the inventory of environments currently kept for a possible resume, and how
 * many ticks in a row ended without a claim verdict.
 *
 * <p>{@code intervalSeconds} is the tick cadence, carried for the same reason {@link ReaperVital}
 * carries its own: the sweep ticks on {@code factory.serve.sandbox-sweep-interval} (default 5
 * minutes), not on the snapshot-write interval, so without it a reader cannot judge {@code
 * lastTickAt} staleness from the file alone.
 *
 * <p>{@code kept} is bounded at {@link #MAX_KEPT_INVENTORY} entries while {@code keptTotal}
 * carries the true count, so truncation is stated in the snapshot rather than silently hiding
 * environments (NFR-O1: "the inventory SHALL be bounded in size, truncation stated").
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements NFR-O1 of add-serve-sandbox-lifecycle.
 *
 * @param lastTickAt when the last sweep tick completed; never null
 * @param intervalSeconds the sweep tick cadence, in seconds — the staleness yardstick for {@code
 *     lastTickAt}, distinct from the top-level {@code intervalSeconds} (the snapshot-write cadence)
 * @param counts the last tick's per-category verdict counts; never null
 * @param kept the kept-environment inventory, at most {@link #MAX_KEPT_INVENTORY} entries, oldest
 *     first; never null, may be empty
 * @param keptTotal how many kept environments the tick actually saw — larger than {@code
 *     kept.size()} exactly when the inventory was truncated; never negative
 * @param consecutiveSkippedTicks how many ticks in a row ended with at least one
 *     skipped-no-verdict object; {@code 0} once a tick reaches verdicts again
 */
public record SweepVital(
        Instant lastTickAt,
        long intervalSeconds,
        SweepCounts counts,
        List<KeptEnvironmentEntry> kept,
        int keptTotal,
        int consecutiveSkippedTicks) {

    /** How many kept environments the inventory carries before truncating (NFR-O1). */
    public static final int MAX_KEPT_INVENTORY = 20;

    public SweepVital {
        kept = List.copyOf(kept);
        keptTotal = requireNonNegative(keptTotal, "keptTotal");
        consecutiveSkippedTicks = requireNonNegative(consecutiveSkippedTicks, "consecutiveSkippedTicks");
    }

    /**
     * Whether the inventory dropped entries — the fact the snapshot must state rather than imply.
     *
     * @return true when {@code keptTotal} exceeds the carried entries
     */
    public boolean keptTruncated() {
        return keptTotal > kept.size();
    }

    /**
     * Kept as a shared static method rather than inline in the compact constructor: PIT's record
     * filter suppresses all mutations inside a record's canonical constructor, which would silently
     * exempt this validation from the 100% mutation gate.
     */
    private static int requireNonNegative(int value, String component) {
        if (value < 0) {
            throw new IllegalArgumentException("SweepVital." + component + " must not be negative");
        }
        return value;
    }
}
