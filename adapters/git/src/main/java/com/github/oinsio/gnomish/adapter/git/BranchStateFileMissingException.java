package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.BranchLocation;
import com.github.oinsio.gnomish.app.port.git.BranchStateResult;
import java.io.Serial;

/**
 * Thrown when the task branch was located ({@link BranchLocation.Local} or {@link
 * BranchLocation.RemoteTracking}) but {@code git show <ref>:.gnomish-task/<file>} fails at its
 * tip — e.g. a {@code Completed} task whose cleanup commit (FR15) already removed {@code
 * .gnomish-task/} from the tip, leaving the state files reachable only in branch history, not at
 * HEAD. Distinct from "branch not found" ({@link BranchStateResult.NotFound}): the branch exists,
 * only the requested file at its tip does not.
 *
 * <p>Inspection no longer raises it: {@link BranchStateReader} and {@link TaskBranchLister} decide
 * what a tip holds through the shape classifier and report a delivered or bare branch as its shape
 * (FR16 of harden-task-branch-contract). What remains is {@link DeliveredBranchReader}'s recovery
 * read of the commit before cleanup, where a missing file really is a broken assumption.
 *
 * <p>Implements FR13 of add-git-workflow.
 */
public final class BranchStateFileMissingException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param ref the fully-qualified ref the file was read from
     * @param filePath the path within the branch that could not be read, e.g. {@code
     *     ".gnomish-task/task.json"}
     * @param gitError the underlying {@code git show} stderr
     */
    public BranchStateFileMissingException(String ref, String filePath, String gitError) {
        super(ref + ": " + filePath + " not found at branch tip (" + gitError.strip() + ")");
    }
}
