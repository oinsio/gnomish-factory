package com.github.oinsio.gnomish.app.lease;

/**
 * The single component answerable for converging one {@link TrackerShape} — the tracker medium's
 * half of the crash-consistency principle "every shape has exactly one recovery owner"
 * (docs/adr/0003-crash-consistency.md). Two owners for one shape is a bug; none is a shape that
 * only gets reported.
 *
 * <p>Implements FR19, FR12 of harden-task-branch-contract.
 */
public enum TrackerRecoveryOwner {

    /** The ordinary claim queue: the task is claimable and needs nothing done to it. */
    QUEUE,

    /** The instance holding the claim, which beats it for as long as it works the task. */
    HOLDER,

    /** A human, who returns the task to the queue when they are ready. */
    HUMAN,

    /** The standing reaper, which repairs the shape on a later sweep tick. */
    REAPER,

    /** Nobody: a terminal state, or a combination no automatic repair may touch. */
    NONE
}
