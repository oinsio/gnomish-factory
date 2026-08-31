package com.github.oinsio.gnomish.app.branch;

import com.github.oinsio.gnomish.domain.branch.BranchShape;
import com.github.oinsio.gnomish.domain.branch.RecoveryDisposition;
import org.jspecify.annotations.Nullable;

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
            // Bare is the one non-quarantine shape whose name explains nothing to an operator: it
            // is what `status` prints for a branch nobody has started work on, and "Bare" alone
            // reads as a fault rather than as the ordinary empty state it is.
            case BranchShape.Bare() ->
                "a task branch carrying no STARTED commit — nothing of the task is recorded on it yet";
            // Every other shape has an owner that converges it, so reaching here is a routing
            // defect rather than a branch state; the name is the whole diagnosis.
            case BranchShape.Created(),
                    BranchShape.InProgress(),
                    BranchShape.Parked(),
                    BranchShape.Answered(),
                    BranchShape.CompletedUncleaned(),
                    BranchShape.Delivered(),
                    BranchShape.StaleEpoch() -> shape.getClass().getSimpleName();
        };
    }

    /**
     * The diagnosis {@code shape} carries where its name is not the whole answer, or {@code null}
     * where it is — the one owner of that question, so the single-task renderer and the list
     * renderer cannot disagree about which shapes explain themselves.
     *
     * <p>Two kinds qualify: the three {@link RecoveryDisposition#QUARANTINE} shapes, whose whole
     * point is the observed-versus-expected detail, and {@link BranchShape.Bare}, an ordinary empty
     * state whose bare name reads to an operator like a fault.
     *
     * @param shape the classifier's verdict; never null
     * @return the phrase to show beside the shape, or {@code null} when the name suffices
     */
    public static @Nullable String diagnosisFor(BranchShape shape) {
        boolean explains = shape.disposition() == RecoveryDisposition.QUARANTINE || shape instanceof BranchShape.Bare;
        return explains ? phrase(shape) : null;
    }
}
