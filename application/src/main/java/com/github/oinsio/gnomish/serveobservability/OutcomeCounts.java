package com.github.oinsio.gnomish.serveobservability;

/**
 * A {@code runSummary} line's outcome counters (FR13): how many slot results
 * of each {@link TaskOutcome} the drain run produced. Explicit named fields
 * rather than a {@code Map<TaskOutcome, Integer>} — the vocabulary is
 * closed to exactly these four (design D6), mirroring {@link
 * VitalsSnapshot}'s explicit-section shape over a map of unbounded keys.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR13 of add-serve-observability.
 *
 * @param delivered count of {@link TaskOutcome#DELIVERED} results; never negative
 * @param awaitingHuman count of {@link TaskOutcome#AWAITING_HUMAN} results; never negative
 * @param aborted count of {@link TaskOutcome#ABORTED} results; never negative
 * @param revoked count of {@link TaskOutcome#REVOKED} results; never negative
 */
public record OutcomeCounts(int delivered, int awaitingHuman, int aborted, int revoked) {

    public OutcomeCounts {
        delivered = requireNonNegative(delivered, "delivered");
        awaitingHuman = requireNonNegative(awaitingHuman, "awaitingHuman");
        aborted = requireNonNegative(aborted, "aborted");
        revoked = requireNonNegative(revoked, "revoked");
    }

    /**
     * Fails fast on a negative count: an outcome cannot be counted a negative
     * number of times. Kept as a shared static method rather than inline in
     * the compact constructor: PIT's record filter suppresses all mutations
     * inside a record's canonical constructor, which would silently exempt
     * this validation from the 100% mutation gate.
     */
    private static int requireNonNegative(int value, String component) {
        if (value < 0) {
            throw new IllegalArgumentException("OutcomeCounts." + component + " must not be negative");
        }
        return value;
    }
}
