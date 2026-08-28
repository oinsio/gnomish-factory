package com.github.oinsio.gnomish.app.branch;

import com.github.oinsio.gnomish.domain.branch.BranchShape;

/**
 * The one rendering of "what was found on the branch" in plain words, shared by everything that has
 * to tell a human what a shape means: the exception that stops the run and the tracker report that
 * explains the stop (FR15, NFR-O2, UX2 of harden-task-branch-contract). One renderer, because a
 * diagnosis that reads differently in the log and in the tracker is two diagnoses to keep in step.
 *
 * <p>Exhaustive by construction with no default branch: a shape added to the closed set has to be
 * named here too (FR2).
 *
 * <p>Implements FR15, NFR-O2, UX2 of harden-task-branch-contract.
 */
public final class BranchShapeDiagnosis {

    private BranchShapeDiagnosis() {}

    /**
     * Describes {@code shape} as a noun phrase that fits after "classifies as", naming the offending
     * file and the observed versus expected content for the three shapes that carry a diagnosis.
     *
     * @param shape the classifier's verdict; never null
     * @return the human-readable phrase; never blank
     */
    public static String phrase(BranchShape shape) {
        return switch (shape) {
            case BranchShape.UnsupportedVersion(String file, int observed, int supported) ->
                file + " declaring version " + observed + " where this factory supports " + supported;
            case BranchShape.Corrupt(String reason) -> "corrupt content (" + reason + ")";
            case BranchShape.Unknown(String reason) -> "an unrecognized combination (" + reason + ")";
            // Every other shape has an owner that converges it, so reaching here is a routing
            // defect rather than a branch state; the name is the whole diagnosis.
            case BranchShape.Bare(),
                    BranchShape.Created(),
                    BranchShape.InProgress(),
                    BranchShape.Parked(),
                    BranchShape.Answered(),
                    BranchShape.CompletedUncleaned(),
                    BranchShape.Delivered(),
                    BranchShape.StaleEpoch() -> shape.getClass().getSimpleName();
        };
    }
}
