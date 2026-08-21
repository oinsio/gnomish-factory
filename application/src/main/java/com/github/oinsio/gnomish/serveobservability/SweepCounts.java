package com.github.oinsio.gnomish.serveobservability;

import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory;
import java.util.Map;

/**
 * One sweep tick's per-category verdict counts, carried both by the snapshot's {@link SweepVital}
 * and by the ledger's {@link SweepTickLine}. Explicit named fields rather than a {@code
 * Map<SweepVerdictCategory, Integer>} — the vocabulary is closed to exactly these six (FR9 of
 * add-serve-sandbox-lifecycle), mirroring {@link OutcomeCounts}'s precedent over the four terminal
 * outcomes.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements NFR-O1, NFR-O2 of add-serve-sandbox-lifecycle.
 *
 * @param checkedAlive objects left untouched (fresh claim, or under the minimum object age)
 * @param keptUnderThreshold unowned stopped boxes and remnants still under the reap threshold
 * @param stoppedOrphan running boxes stopped because they were unowned
 * @param disposedAged kept environments and remnants disposed past the reap threshold
 * @param disposedReconstructible guard/judge/verification/seed-helper objects disposed at once
 * @param skippedNoVerdict objects no verdict was reached for — a tracker outage left ownership
 *     unknown, or the runtime refused the action the matrix decided on
 */
public record SweepCounts(
        int checkedAlive,
        int keptUnderThreshold,
        int stoppedOrphan,
        int disposedAged,
        int disposedReconstructible,
        int skippedNoVerdict) {

    /** All-zero counts: a tick that evaluated no object at all. */
    public static final SweepCounts NONE = new SweepCounts(0, 0, 0, 0, 0, 0);

    public SweepCounts {
        checkedAlive = requireNonNegative(checkedAlive, "checkedAlive");
        keptUnderThreshold = requireNonNegative(keptUnderThreshold, "keptUnderThreshold");
        stoppedOrphan = requireNonNegative(stoppedOrphan, "stoppedOrphan");
        disposedAged = requireNonNegative(disposedAged, "disposedAged");
        disposedReconstructible = requireNonNegative(disposedReconstructible, "disposedReconstructible");
        skippedNoVerdict = requireNonNegative(skippedNoVerdict, "skippedNoVerdict");
    }

    /**
     * Projects a per-category tally onto the six named fields, defaulting absent categories to
     * zero — the one place the tick recorder's open map becomes this closed record.
     *
     * @param tally counts keyed by category; categories absent from the map count as zero
     * @return the equivalent named counts
     */
    public static SweepCounts of(Map<SweepVerdictCategory, Integer> tally) {
        return new SweepCounts(
                count(tally, SweepVerdictCategory.CHECKED_ALIVE),
                count(tally, SweepVerdictCategory.KEPT_UNDER_THRESHOLD),
                count(tally, SweepVerdictCategory.STOPPED_ORPHAN),
                count(tally, SweepVerdictCategory.DISPOSED_AGED),
                count(tally, SweepVerdictCategory.DISPOSED_RECONSTRUCTIBLE),
                count(tally, SweepVerdictCategory.SKIPPED_NO_VERDICT));
    }

    private static int count(Map<SweepVerdictCategory, Integer> tally, SweepVerdictCategory category) {
        return tally.getOrDefault(category, 0);
    }

    /**
     * Fails fast on a negative count: a category cannot be reached a negative number of times.
     * Kept as a shared static method rather than inline in the compact constructor: PIT's record
     * filter suppresses all mutations inside a record's canonical constructor, which would silently
     * exempt this validation from the 100% mutation gate.
     */
    private static int requireNonNegative(int value, String component) {
        if (value < 0) {
            throw new IllegalArgumentException("SweepCounts." + component + " must not be negative");
        }
        return value;
    }
}
