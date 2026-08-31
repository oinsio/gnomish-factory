package com.github.oinsio.gnomish.app.port.git;

import java.io.Serial;

/**
 * Thrown when the task-branch lookup exhausted its infrastructure retries without establishing
 * whether the branch exists ({@link BranchLocation.Unavailable}), so the take aborts rather than
 * routing to a fresh claim (FR6).
 *
 * <p>Distinct from {@link TaskGit}'s ordinary failures because it is not a defect and not a
 * usage error: origin was unreachable, and the only safe answer is to stop. The take's own
 * crash-abort protocol releases the claim, so the task returns to the pool for an instance whose
 * network works — routing it to a fresh claim instead is what forked a duplicate branch.
 *
 * <p>Implements FR6 of harden-task-branch-contract.
 */
public final class BranchLocationUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param taskId the task whose branch could not be located; never blank
     * @param reason what stopped the lookup, from {@link BranchLocation.Unavailable#reason()}
     */
    public BranchLocationUnavailableException(String taskId, String reason) {
        super("could not establish whether the task branch for " + taskId + " exists on origin: " + reason
                + "; aborting instead of claiming a fresh branch");
    }
}
