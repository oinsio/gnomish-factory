package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
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
     * @param detail the command's captured stderr, as the git evidence; may be blank. An
     *     {@link UntrustedText} rather than a rendered {@code String}: the detail is git's own
     *     words, so the carrier reaches the message through its log exit
     *     {@link UntrustedText#forLog()} here instead of at the call site (design D5 of
     *     type-untrusted-text). This message is rendered into a log record, and the log-call gate
     *     cannot see inside an exception's text — so the rendering happens where the untrusted
     *     text enters it ({@code .claude/rules/logging.md}, "Carry untrusted text in
     *     UntrustedText; neutralize it at the exit").
     */
    public TaskListingFailedException(String pattern, int exitCode, UntrustedText detail) {
        super("could not enumerate " + pattern + ": git for-each-ref exited " + exitCode
                + "; refusing to print an empty task table for a listing that never ran: " + detail.forLog());
    }
}
