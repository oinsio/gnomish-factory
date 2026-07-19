package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.status.StatusReport;

/**
 * The outcome of {@link BranchStateReader#read}: either the task branch was located and its {@code
 * .gnomish-task/} files rendered into a {@link StatusReport}, or no branch exists anywhere for the
 * requested task. Modeled as a sealed interface rather than a thrown exception because "task not
 * found" is a legitimate, caller-decidable outcome — a merged-and-deleted branch, a typo'd task id
 * — not a defect, matching the {@link BranchLocation} precedent this reader is built on.
 *
 * <p>Implements FR13 of add-git-workflow.
 */
public sealed interface BranchStateResult {

    /**
     * The task branch was found and its state files read successfully.
     *
     * @param report the rendered status report, live-only fields null (design D13)
     */
    record Found(StatusReport report) implements BranchStateResult {}

    /**
     * No {@code gnomish/<task>} branch exists locally, as a remote-tracking ref, or on {@code
     * origin} — including after the narrow fetch attempt (see {@link BranchLocation.NotFound}).
     */
    record NotFound() implements BranchStateResult {}
}
