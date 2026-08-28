package com.github.oinsio.gnomish.domain.branch;

/**
 * The single component responsible for converging one branch shape to a clean state. Exactly one
 * owner per shape; two owners for one shape is a bug (see {@code docs/glossary.md}, "recovery
 * owner"). The owner-per-shape table is owned by {@code docs/adr/0003-crash-consistency.md}; this
 * enum names the components that table points at, and {@link BranchShape#recoveryOwner()} realizes
 * the mapping.
 *
 * <p>Implements FR1, FR2 of harden-task-branch-contract.
 */
public enum RecoveryOwner {

    /** Take routing: creates the task branch's STARTED commit for a ref that carries none. */
    TAKE_ROUTING,

    /** The stage engine: resumes the pipeline at the position the branch records. */
    STAGE_ENGINE,

    /** The terminal-transition component: completes a park's pending tracker write, then waits. */
    TERMINAL_TRANSITION,

    /** The completion-finish flow: cleanup, push, tracker finish — never a re-entry of the engine. */
    COMPLETION_FINISH,

    /** The replica-pair reconciler: discards artifacts that lost to the live claim's tip. */
    REPLICA_RECONCILER,

    /** The recovery budget: quarantines to the needs-human status with a diagnosis. */
    RECOVERY_BUDGET,

    /** Nobody: the shape is terminal and there is nothing left to converge. */
    NONE
}
