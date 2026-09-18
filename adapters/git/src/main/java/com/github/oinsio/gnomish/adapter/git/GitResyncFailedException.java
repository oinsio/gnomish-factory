package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.subprocess.Termination;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.Serial;

/**
 * Thrown when a working tree could not be brought onto the ref line its clone just adopted: the
 * local ref moved to origin's tip, but {@code reset --hard} or {@code clean -fd} failed, so the
 * tree still holds the discarded line. Continuing would let salvage commit that line back on top of
 * the adopted tip, which is the corruption {@link WorktreeResync} exists to prevent — so the
 * failure is reported rather than absorbed.
 *
 * <p>A named type rather than the {@link IllegalStateException} it replaces, so the constructor can
 * declare that its detail is untrusted text and the compiler keeps a raw {@code String} out of it
 * (design D5 of type-untrusted-text).
 *
 * <p>Implements FR8 of harden-task-branch-contract; FR5 of type-untrusted-text.
 */
public final class GitResyncFailedException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param context what was being resynced, in the factory's own words
     * @param command the git command that failed, e.g. {@code "git clean -fd"}
     * @param termination how that invocation ended
     * @param exitCode its exit code
     * @param stderr git's own words; rendered through the log exit
     */
    public GitResyncFailedException(
            String context, String command, Termination termination, int exitCode, UntrustedText stderr) {
        super(context + "; " + command + " failed (" + termination + ", exit " + exitCode + "): " + stderr
                + ". Continuing would let salvage commit the discarded line back on top of the adopted tip; run that"
                + " command in the working tree by hand, then resume the task.");
    }
}
