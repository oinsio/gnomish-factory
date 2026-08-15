package com.github.oinsio.gnomish.adapter.git;

import java.io.Serial;

/**
 * Thrown when {@link RoundBoundaryCheck} finds that the gnome broke the round-boundary git
 * discipline (design D12): HEAD left the task branch, the previous round's tip is no longer an
 * ancestor of HEAD (history rewrite), or the gnome modified {@code .gnomish-task/} directly. This
 * is a distinct failure class from {@link GitPersistFailedException} — that type means "the git
 * command or filesystem I/O itself failed"; this one means "the working tree is intact and git
 * succeeded, but its state violates the protocol the adapter relies on" — worth telling apart in
 * logs and error messages (NFR-O2).
 *
 * <p>Violation breaks durability: the caller (the round-boundary check runs before the round
 * commit in {@link GitAttemptPersistence#persist}) never reaches the commit step, so {@code
 * persist} throws and the engine turns it into an {@code Aborted} outcome.
 *
 * <p>Implements FR12 of add-git-workflow.
 */
public final class RoundBoundaryViolationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param taskId the task whose round-boundary check failed
     * @param reason what protocol rule was violated, e.g. {@code "HEAD is not on the task
     *     branch"} or {@code ".gnomish-task/ was modified by the gnome"}
     */
    public RoundBoundaryViolationException(String taskId, String reason) {
        super("round-boundary protocol violated for taskId \"" + taskId + "\": " + reason);
    }
}
