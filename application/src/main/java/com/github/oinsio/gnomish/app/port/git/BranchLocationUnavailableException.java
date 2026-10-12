package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.Serial;

/**
 * Thrown when the task-branch lookup exhausted its infrastructure retries without establishing
 * whether the branch exists ({@link BranchLocation.Unavailable}), so the take aborts rather than
 * routing to a fresh claim (FR6).
 *
 * <p>Distinct from {@link TaskGit}'s ordinary failures because it is not a defect and not a
 * usage error: origin was unreachable, and the only safe answer is to stop. In a take it is a
 * claim-time fetch outage, so the take releases the claim plain, outside the abort accounting, as
 * it does a base-refresh outage (finding F6 of tighten-spec-contract; FR9 of
 * add-base-ref-resolution): the task returns to the pool for an instance whose network works —
 * routing it to a fresh claim instead is what forked a duplicate branch. The {@link #reason()} is
 * what the released-on-outage result quotes.
 *
 * <p>Implements FR6 of harden-task-branch-contract.
 */
public final class BranchLocationUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient UntrustedText reason;

    /**
     * The reason is a sentence the factory composed around git's stderr, so it arrives as a
     * carrier and leaves it here through the log exit {@link UntrustedText#forLog()}. This message
     * is rendered into a log record and into the abort diagnosis, and the log-call gate cannot see
     * inside an exception's text — so the rendering happens where the untrusted text enters it
     * ({@code .claude/rules/logging.md}, "Carry untrusted text in UntrustedText; neutralize it at
     * the exit").
     *
     * @param taskId the task whose branch could not be located; never blank
     * @param reason what stopped the lookup, from {@link BranchLocation.Unavailable#reason()}
     */
    public BranchLocationUnavailableException(String taskId, UntrustedText reason) {
        super("could not establish whether the task branch for " + taskId + " exists on origin: " + reason.forLog()
                + "; stopping instead of claiming a fresh branch");
        this.reason = reason;
    }

    /**
     * What stopped the lookup, still carried, for a caller that reports the outage in its own
     * sentence rather than rethrowing this exception.
     *
     * @return the reason the lookup gave; never null
     */
    public UntrustedText reason() {
        return reason;
    }
}
