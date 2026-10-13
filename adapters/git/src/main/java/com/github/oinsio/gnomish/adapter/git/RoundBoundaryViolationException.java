package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.Serial;

/**
 * Thrown when an admission point finds that the gnome broke the round-boundary git discipline
 * (design D12; design D18 of tighten-spec-contract): HEAD left the task branch, the previous tip is
 * no longer an ancestor (history rewrite), a commit has a second parent or a {@code gnomish: }
 * subject, or the gnome modified {@code .gnomish-task/} — judged by the one {@link
 * GnomeRangeInspection}, at the host round close and at the harvest admission alike. This
 * is a distinct failure class from {@link GitPersistFailedException} — that type means "the git
 * command or filesystem I/O itself failed"; this one means "the working tree is intact and git
 * succeeded, but its state violates the protocol the adapter relies on" — worth telling apart in
 * logs and error messages (NFR-O2).
 *
 * <p>Violation breaks durability: the caller never reaches the step that would make the refused
 * content branch state, so {@code persist} throws and the engine turns it into an {@code Aborted}
 * outcome.
 *
 * <p>Implements FR12 of add-git-workflow; FR13 of tighten-spec-contract.
 */
public final class RoundBoundaryViolationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param taskId the task whose round-boundary check failed
     * @param reason what protocol rule was violated, e.g. {@code "HEAD is not on the task
     *     branch"} or {@code ".gnomish-task/ was modified by the gnome"}. An {@link UntrustedText}
     *     because one arm of the check quotes what git printed — the paths the gnome touched — and
     *     a {@code String} parameter there would be the escape hatch design D5 of
     *     type-untrusted-text closes: the carrier composes the message through its log exit.
     */
    public RoundBoundaryViolationException(String taskId, UntrustedText reason) {
        super("round-boundary protocol violated for taskId \"" + taskId + "\": " + reason.forLog());
    }
}
