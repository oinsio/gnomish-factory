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
 * <p>The same refusal covers a read that <em>did</em> run and still established nothing: a
 * resolution that exited non-zero, or exited cleanly with no ref on stdout, yields the empty
 * string — which travels on as a commit unless it is refused here (FR13 of
 * harden-logging-observability). A blank tip recorded into an attempt record outlives the process;
 * a blank tip compared against the last observed one reports movement that never happened.
 *
 * <p>Not a defect and not a usage error: the take aborts through its crash-abort protocol, which
 * releases the claim so the task returns to the pool.
 *
 * <p>Implements FR1, FR6, FR13 of harden-task-branch-contract; FR13 of
 * harden-logging-observability.
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

    /**
     * @param revision the ref or revision the read was made at; never blank
     * @param command the git subcommand that ran but established no tip; never blank
     * @param exitCode the command's exit code; {@code 0} when it exited cleanly with no ref
     * @param detail the command's captured stderr, as the evidence for the failure; may be blank
     */
    public BranchTipUnavailableException(String revision, String command, int exitCode, String detail) {
        super("could not read the task branch tip at " + revision + ": git " + command + " exited " + exitCode
                + " without printing a ref; refusing to record or compare a blank tip: " + detail);
    }
}
