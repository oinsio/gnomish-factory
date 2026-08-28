package com.github.oinsio.gnomish.app.port.tracker;

/**
 * The kind of the newest claim-boundary marker a task's record carries after its newest claim
 * marker — the fact that says "this tenure ended, and how" (design D16). A boundary marker is the
 * truth; the state labels are only its index, so a boundary observed while the task still wears the
 * working label is the {@code IndexLagging} shape whose flip the reaper completes.
 *
 * <p>The four kinds are the session-ending writes of the port: {@code recordAbort}, {@code park},
 * {@code finish}, and the reaper's own stale-claim removal. The index-repair marker is deliberately
 * NOT one of them: it records that a repair ran, implies no state of its own, and so must never
 * displace the boundary whose flip it is completing.
 *
 * <p>Implements FR19, FR12 of harden-task-branch-contract.
 */
public enum BoundaryKind {

    /** An abort ended the tenure; the labels it implies are {@code Ready}. */
    ABORT,

    /** A park ended the tenure; the labels it implies are {@code AwaitingHuman}. */
    PARK,

    /** A finish ended the tenure; the labels it implies are {@code Finished}. */
    FINISH,

    /** A reaper removed the tenure's claim; the labels it implies are {@code Ready}. */
    STALE_CLAIM_REMOVED
}
