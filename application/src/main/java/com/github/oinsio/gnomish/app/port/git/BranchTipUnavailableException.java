package com.github.oinsio.gnomish.app.port.git;

import java.io.Serial;

/**
 * Thrown when a read of the task branch's tip did not run to its own exit — cut off on a deadline
 * or interrupted by a shutdown — so it established nothing about what the tip carries.
 *
 * <p>The sibling of {@link BranchLocationUnavailableException} one step further in: that one is
 * raised when origin never said whether the branch exists, this one when the branch was located
 * but its content could not be read. Both exist for the same reason — an invocation that never
 * answered must not be read as a positive fact. A file read that answers "absent" on an interrupt
 * classifies a live branch as {@code Bare}, which routes the take to a fresh claim and forks a
 * second branch for a task that already has one; an epoch read that answers "unstamped" silently
 * disables the staleness fence; a history search that answers "no cleanup commit" un-delivers a
 * finished branch.
 *
 * <p>Not a defect and not a usage error: the take aborts through its crash-abort protocol, which
 * releases the claim so the task returns to the pool.
 *
 * <p>Implements FR1, FR6, FR13 of harden-task-branch-contract.
 */
public final class BranchTipUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param revision the ref or revision the read was made at; never blank
     * @param command the git subcommand that did not run to its own exit; never blank
     * @param termination how the invocation ended instead of exiting, by its named outcome
     */
    public BranchTipUnavailableException(String revision, String command, String termination) {
        super("could not read the task branch tip at " + revision + ": git " + command
                + " did not run to its own exit (" + termination
                + "); aborting instead of reading its outcome as a fact about the tip");
    }
}
