package com.github.oinsio.gnomish.board;

import java.util.List;

/**
 * The Ready column's reconciled summary counts (FR3 of add-board-command):
 * total queued, eligible now, and the ineligible breakdown by reason — in
 * backoff, {@code finished}, WIP-held — in the feed's own precedence order
 * (design D7). Every ready row falls into exactly one bucket (eligible, or
 * exactly one ineligible reason), so {@code eligibleNowCount +
 * inBackoffCount + finishedCount + wipHeldCount} always equals {@code
 * queuedCount}; the compact constructor enforces this defensively.
 *
 * <p>All counts are scoped to the fetched Ready window (FR6's {@code
 * --limit}) — on a truncated window (see {@link BoardModel#truncated()}),
 * {@code queuedCount} describes the shown entries, not the tracker's full
 * queue. Tallying is identical either way: this type has no truncation-aware
 * branch because none is needed.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR3 of add-board-command.
 *
 * @param queuedCount      the number of ready tasks in the fetched window; never
 *                         negative
 * @param eligibleNowCount tasks the feed would claim now, i.e. rows with no
 *                         eligibility reason; never negative
 * @param inBackoffCount   tasks currently backed off; never negative
 * @param finishedCount    tasks whose recorded history is terminal; never
 *                         negative
 * @param wipHeldCount     fresh tasks skipped for the WIP gate; never negative
 */
public record ReadySummary(
        int queuedCount, int eligibleNowCount, int inBackoffCount, int finishedCount, int wipHeldCount) {

    public ReadySummary {
        requireNonNegative(queuedCount, "queuedCount");
        requireNonNegative(eligibleNowCount, "eligibleNowCount");
        requireNonNegative(inBackoffCount, "inBackoffCount");
        requireNonNegative(finishedCount, "finishedCount");
        requireNonNegative(wipHeldCount, "wipHeldCount");
        requireReconciled(queuedCount, eligibleNowCount, inBackoffCount, finishedCount, wipHeldCount);
    }

    /**
     * Tallies {@code readyRows} into a reconciled {@code ReadySummary}: one
     * pass, switching each row's {@link ReadyRow#eligibilityReason()} into
     * exactly one bucket ({@code null} means eligible now).
     *
     * @param readyRows the Ready column's rows; never null
     * @return the reconciled summary over {@code readyRows}
     */
    public static ReadySummary tally(List<ReadyRow> readyRows) {
        int eligibleNow = 0;
        int inBackoff = 0;
        int finished = 0;
        int wipHeld = 0;
        for (ReadyRow row : readyRows) {
            switch (row.eligibilityReason()) {
                case null -> eligibleNow++;
                case EligibilityReason.InBackoff ignored -> inBackoff++;
                case EligibilityReason.Finished ignored -> finished++;
                case EligibilityReason.WipHeld ignored -> wipHeld++;
            }
        }
        return new ReadySummary(readyRows.size(), eligibleNow, inBackoff, finished, wipHeld);
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException("ReadySummary." + name + " must not be negative");
        }
    }

    private static void requireReconciled(
            int queuedCount, int eligibleNowCount, int inBackoffCount, int finishedCount, int wipHeldCount) {
        int reconciled = eligibleNowCount + inBackoffCount + finishedCount + wipHeldCount;
        if (reconciled != queuedCount) {
            throw new IllegalArgumentException("ReadySummary counts do not reconcile: eligibleNowCount ("
                    + eligibleNowCount + ") + inBackoffCount (" + inBackoffCount + ") + finishedCount ("
                    + finishedCount + ") + wipHeldCount (" + wipHeldCount + ") = " + reconciled
                    + ", expected queuedCount (" + queuedCount + ")");
        }
    }
}
