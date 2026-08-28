package com.github.oinsio.gnomish.app.branch;

import com.github.oinsio.gnomish.domain.branch.BranchShape;
import java.io.Serial;

/**
 * Thrown when a task branch classifies to one of the three shapes no automatic recovery can
 * converge — an unsupported envelope version, unreadable content, or a combination the contract
 * does not recognize — so the run stops instead of guessing what the branch means (FR15).
 *
 * <p>These shapes bypass the recovery budget by design: retrying a branch whose content cannot be
 * read produces the same unreadable content, so the first classification is the decision. What a
 * human needs is the diagnosis, which the shape carries and this message quotes.
 *
 * <p>Implements FR15 of harden-task-branch-contract.
 */
public final class BranchQuarantineException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient BranchShape shape;

    /**
     * @param taskId the task whose branch cannot be recovered; never blank
     * @param shape the classified shape, whose own diagnosis is quoted; never null
     */
    public BranchQuarantineException(String taskId, BranchShape shape) {
        super("the task branch for " + taskId + " classifies as " + BranchShapeDiagnosis.phrase(shape)
                + ", which no automatic recovery can converge; a human has to look at the branch");
        this.shape = shape;
    }

    /**
     * The shape that stopped the run, so the tracker-facing quarantine report names what was found
     * rather than re-deriving it (NFR-O2).
     *
     * @return the classified shape; never null
     */
    public BranchShape shape() {
        return shape;
    }
}
