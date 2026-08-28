package com.github.oinsio.gnomish.domain.branch;

/**
 * The terminal outcome a branch tip records, reduced to what classification needs: whether a human
 * is awaited, whether delivery is pending, or neither. The four recorded outcome kinds collapse to
 * two here — {@code Paused}, {@code Escalated} and {@code Aborted} all park a task for a human,
 * while {@code Completed} alone leads to delivery.
 *
 * <p>Implements FR1 of harden-task-branch-contract.
 */
public enum RecordedTerminal {

    /** No outcome recorded: a visit is in progress, or none has run yet. */
    NONE,

    /** An outcome is recorded and a human is awaited. */
    PARKED,

    /** Every stage passed; what remains is cleanup and the tracker finish. */
    COMPLETED
}
