package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.domain.branch.BranchShape;
import com.github.oinsio.gnomish.status.StatusReport;

/**
 * The outcome of the git adapter's branch-state reader: either the task branch was located and its
 * {@code .gnomish-task/} files rendered into a {@link StatusReport}, or no branch exists anywhere
 * for the requested task. Modeled as a sealed interface rather than a thrown exception because
 * "task not found" is a legitimate, caller-decidable outcome — a merged-and-deleted branch, a
 * typo'd task id — not a defect, matching the {@link BranchLocation} precedent this reader is built
 * on.
 *
 * <p>A branch whose tip carries no readable {@code .gnomish-task/} envelopes — delivered, bare, or
 * one of the three quarantine shapes — is neither of those two: it is a {@link Shaped} answer
 * naming what the classifier saw, so inspection reports every legal shape calmly instead of failing
 * on the files a shape does not have (FR16 of harden-task-branch-contract).
 *
 * <p>Implements FR13 of add-git-workflow; FR16 of harden-task-branch-contract.
 */
public sealed interface BranchStateResult {

    /**
     * The task branch was found and its state files read successfully.
     *
     * @param report the rendered status report, live-only fields null (design D13)
     */
    record Found(StatusReport report) implements BranchStateResult {}

    /**
     * The task branch was found and classified, but its tip carries no report to render — {@link
     * BranchShape#tipCarriesState()} is false, or the tip is a pre-contract {@link
     * BranchShape.Created} one holding {@code task.json} without {@code state.json} (FR3). The
     * caller renders the shape; the three quarantine shapes are the ones that refuse inspection.
     *
     * @param shape the classifier's verdict for this branch; never null
     */
    record Shaped(BranchShape shape) implements BranchStateResult {}

    /**
     * No {@code gnomish/<task>} branch exists locally, as a remote-tracking ref, or on {@code
     * origin} — including after the narrow fetch attempt (see {@link BranchLocation.NotFound}).
     */
    record NotFound() implements BranchStateResult {}
}
