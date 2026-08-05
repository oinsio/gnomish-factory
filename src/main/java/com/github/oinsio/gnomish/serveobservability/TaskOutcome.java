package com.github.oinsio.gnomish.serveobservability;

import com.github.oinsio.gnomish.app.take.TakeResult;

/**
 * The ledger's {@code taskOutcome.outcome} vocabulary: the four {@link
 * TakeResult} variants that carry a final task state, and therefore get a
 * ledger line (design D6). {@link TakeResult.EmptyQueue} and {@link
 * TakeResult.Skipped} have no counterpart here — no engine run happened, so
 * no line is written for them (FR11).
 *
 * <p>Implements FR11 of add-serve-observability.
 */
public enum TaskOutcome {
    /** Mirrors {@link TakeResult.Delivered}. */
    DELIVERED,
    /** Mirrors {@link TakeResult.AwaitingHuman}; the only outcome with a {@code parkReason}. */
    AWAITING_HUMAN,
    /** Mirrors {@link TakeResult.Aborted}. */
    ABORTED,
    /** Mirrors {@link TakeResult.Revoked}. */
    REVOKED
}
