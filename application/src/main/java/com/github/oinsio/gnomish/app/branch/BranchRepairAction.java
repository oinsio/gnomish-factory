package com.github.oinsio.gnomish.app.branch;

import com.github.oinsio.gnomish.domain.branch.BranchShape;

/**
 * What the routing point is about to do about a non-clean shape, in plain words — the {@code action}
 * half of the repair line (NFR-O1 of harden-task-branch-contract).
 *
 * <p>Rendered from the shape's own {@linkplain BranchShape#disposition() disposition} rather than
 * from a second shape table: the disposition IS the decision the routing table then makes, so a
 * shape added to the closed set inherits its phrase from the one place that already names what its
 * owner does with it. Exhaustive by construction with no default branch, like its sibling {@link
 * BranchShapeDiagnosis}.
 *
 * <p>Implements NFR-O1 of harden-task-branch-contract.
 */
public final class BranchRepairAction {

    private BranchRepairAction() {}

    /**
     * Describes what {@code shape}'s recovery owner is about to do, as a phrase that fits after
     * "action=".
     *
     * @param shape the classifier's verdict; never null
     * @return the human-readable phrase; never blank
     */
    public static String phrase(BranchShape shape) {
        return switch (shape.disposition()) {
            case ROLL_FORWARD -> "resuming from the recorded position";
            case DISCARD -> "reconciling the tip against origin, then classifying it again";
            case QUARANTINE -> "parking for a human on this first classification";
            case TERMINAL -> "finishing the delivered branch";
        };
    }
}
