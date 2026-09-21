package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.BranchLocation;
import com.github.oinsio.gnomish.app.port.git.BranchStateResult;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.Serial;

/**
 * Thrown when the task branch was located ({@link BranchLocation.Local} or {@link
 * BranchLocation.RemoteTracking}) and a {@code git show <revision>:.gnomish-task/<file>} ran to
 * its own exit, but answered that the revision carries no such file — e.g. a {@code Completed}
 * task whose cleanup commit (FR15) already removed {@code .gnomish-task/}, leaving the state files
 * reachable only in branch history. The revision is not necessarily the tip: {@link
 * DeliveredBranchReader} reads the cleanup commit's parent.
 *
 * <p>This type states an <em>absence</em> and never an <em>unavailability</em>. It is distinct
 * from "branch not found" ({@link BranchStateResult.NotFound}) — there the branch itself is
 * missing, here it exists and only the requested file at that revision does not — and distinct
 * from a read that never answered, which is {@link
 * com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException}. A caller may therefore
 * throw this only once {@link GitReadGate#answered} has established that the invocation reached
 * its own exit: a non-zero exit code alone does not establish it, since an interrupted or
 * timed-out {@code git show} exits non-zero too while saying nothing about what the revision
 * carries. Reporting an unanswered read as a missing file is the defect that gate exists to
 * prevent.
 *
 * <p>{@link DeliveredBranchReader}'s recovery read of the commit before cleanup is the only
 * thrower, and there a missing file really is a broken assumption. Inspection no longer raises it:
 * {@link BranchStateReader} and {@link TaskBranchLister} decide what a tip holds through the shape
 * classifier and report a delivered or bare branch as its shape (FR16 of
 * harden-task-branch-contract).
 *
 * <p>Implements FR13 of add-git-workflow.
 */
public final class BranchStateFileMissingException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param ref the revision the file was read from — a fully-qualified ref, or a revision
     *     expression such as {@code <ref>^} that selects one commit on it
     * @param filePath the path within the branch that could not be read, e.g. {@link
     *     EnvelopePaths#TASK_JSON_PATH}
     * @param gitError the underlying {@code git show} stderr; it arrives as a carrier and leaves
     *     it here through the log exit {@link UntrustedText#forLog()}, because this message is
     *     rendered into a log record and the log-call gate cannot see inside an exception's text
     *     ({@code .claude/rules/logging.md}, "Carry untrusted text in UntrustedText; neutralize it
     *     at the exit")
     */
    public BranchStateFileMissingException(String ref, String filePath, UntrustedText gitError) {
        super(ref + ": " + filePath + " not found at that revision (" + gitError.forLog() + ")");
    }
}
