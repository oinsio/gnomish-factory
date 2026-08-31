package com.github.oinsio.gnomish.app.port.git;

import java.io.Serial;

/**
 * Thrown when the first push of a newly created task branch did not reach {@code origin} within
 * its bounded retries, so the take aborts before any round starts (FR7).
 *
 * <p>Every later push on the branch is best-effort — durability is the recorded branch state, and
 * a lost push is caught up at the next touchpoint. This one is not: a claim that proceeds on a
 * branch origin has never seen leaves the fleet with work no other instance can find, and the
 * heartbeat's own recovery cannot converge a branch that exists nowhere but one disk.
 *
 * <p>Implements FR7 of harden-task-branch-contract.
 */
public final class FirstPushFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param taskId the task whose branch could not be published; never blank
     * @param branch the task branch name; never blank
     * @param reason what the last attempt established, e.g. a timed-out push with an unknown
     *     remote outcome
     */
    public FirstPushFailedException(String taskId, String branch, String reason) {
        super("the first push of " + branch + " for " + taskId + " did not reach origin: " + reason
                + "; aborting before any round starts rather than working on a branch origin has never seen");
    }
}
