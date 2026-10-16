package com.github.oinsio.gnomish.domain.branch;

/**
 * The single component responsible for converging one branch shape to a clean state. Exactly one
 * owner per shape; two owners for one shape is a bug (see {@code docs/glossary.md}, "recovery
 * owner"). The owner-per-shape table is owned by {@code docs/adr/0003-crash-consistency.md}; this
 * enum names the components that table points at, and {@link BranchShape#recoveryOwner()} realizes
 * the mapping.
 *
 * <p>Implements FR1, FR2 of harden-task-branch-contract; FR1 of fix-claim-epoch-fence; FR10 of
 * fix-terminal-receipt-convergence.
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

    /**
     * The touchpoint fetch: lands the task's register beside its branch in one transfer, after which
     * the branch is what its content says (design D13 of fix-terminal-receipt-convergence).
     *
     * <p>Implements FR10 of fix-terminal-receipt-convergence.
     */
    TOUCHPOINT_FETCH,

    /** The recovery budget: quarantines to the needs-human status with a diagnosis. */
    RECOVERY_BUDGET,

    /** Nobody: the shape is terminal and there is nothing left to converge. */
    NONE
}
