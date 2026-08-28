package com.github.oinsio.gnomish.domain.branch;

/**
 * What a shape's recovery owner does with the frozen state it finds: complete the transition the
 * state was part of, return to a known-good tip, stop at a human, or nothing at all. The
 * disposition-per-shape table is owned by {@code docs/adr/0003-crash-consistency.md}; {@link
 * BranchShape#disposition()} realizes the mapping.
 *
 * <p>Implements FR1, FR2 of harden-task-branch-contract.
 */
public enum RecoveryDisposition {

    /** Complete the transition the frozen state was part of. */
    ROLL_FORWARD,

    /** Return to a known-good tip, dropping what was lost. */
    DISCARD,

    /** Hand the task to a human with a diagnosis, without consuming recovery budget. */
    QUARANTINE,

    /** Nothing to recover: the shape is already the end state. */
    TERMINAL
}
