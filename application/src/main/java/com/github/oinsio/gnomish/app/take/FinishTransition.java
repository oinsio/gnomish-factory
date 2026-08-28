package com.github.oinsio.gnomish.app.take;

/**
 * The branch-side steps of one completion, in the two ways a completion is reached (FR10, design D5
 * of harden-task-branch-contract): fresh — the {@code Completed} outcome commit has yet to be
 * written — or recovered, a tip already recording {@code Completed} whose tracker finish never
 * confirmed.
 *
 * <p>Both carry the same destructive tail: the cleanup commit that removes {@code .gnomish-task/}
 * from the tip, plus the workspace disposal that follows it. It runs last of all, behind the
 * confirmed finish, so a tip recording {@code Completed} without it is always a finished task
 * awaiting cleanup — never one to re-execute (FR9).
 *
 * <p>Implements FR9, FR10 of harden-task-branch-contract.
 */
public sealed interface FinishTransition {

    /**
     * The destructive tail: the cleanup commit and the workspace disposal behind it.
     *
     * @return the cleanup step; never null
     */
    Runnable cleanup();

    /**
     * A completion happening now.
     *
     * @param intent records the {@code Completed} outcome commit and delivers it to origin
     * @param cleanup the destructive tail, run only behind a confirmed finish
     */
    record Fresh(Runnable intent, Runnable cleanup) implements FinishTransition {}

    /**
     * A completion whose outcome is already on the branch and whose tracker finish is still owed. A
     * tip already cleaned passes a cleanup that does nothing — the step stays idempotent either way.
     *
     * @param cleanup the destructive tail, run only behind a confirmed finish
     */
    record Recovered(Runnable cleanup) implements FinishTransition {}
}
