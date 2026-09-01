package com.github.oinsio.gnomish.app.port.git;

import java.io.Serial;

/**
 * Thrown when the enumeration behind {@code gnomish status}'s list mode failed: the ref listing
 * exited non-zero, so which {@code gnomish/*} branches exist was never established.
 *
 * <p>Per-branch degradation stops at the branch (FR16 of harden-task-branch-contract): one
 * unreadable branch renders as its own diagnostic row and the rest of the table still prints. The
 * enumeration is not a branch — its failure is the whole table's failure, because an empty table
 * is a positive claim. "Verified: this clone holds no tasks" and "could not look" are opposite
 * answers, and printing the first for the second is the most misleading thing this read-only
 * command can do (FR13 of harden-logging-observability).
 *
 * <p>Implements FR13 of harden-logging-observability.
 */
public final class TaskListingFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param pattern the ref pattern that could not be enumerated; never blank
     * @param exitCode the failing {@code for-each-ref} invocation's exit code
     * @param detail the command's captured stderr, as the git evidence; may be blank
     */
    public TaskListingFailedException(String pattern, int exitCode, String detail) {
        super("could not enumerate " + pattern + ": git for-each-ref exited " + exitCode
                + "; refusing to print an empty task table for a listing that never ran: " + detail);
    }
}
