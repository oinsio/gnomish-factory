package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.domain.branch.BranchShape;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code gnomish status}' list mode (FR13): a task branch's tip summary — just enough
 * for the table, not a full {@link com.github.oinsio.gnomish.status.StatusReport}. Produced by
 * {@code TaskBranchLister} (the git adapter's lister), one row per deduplicated task.
 *
 * <p>Every branch yields a row whatever its shape (FR16 of harden-task-branch-contract), so the
 * row carries the classifier's verdict alongside the content fields: a branch whose tip carries no
 * readable envelopes has no stage, attempts or outcome to report, and its {@code shape} is the
 * whole row. The shape travels as the domain type rather than a pre-rendered string — how a shape
 * reads to a human belongs to the renderer, not to the reader that classified it.
 *
 * <p>Implements FR13 of add-git-workflow; FR16 of harden-task-branch-contract.
 *
 * @param taskId the authoritative taskId, read from {@code task.json} at the branch tip — never
 *     parsed from the branch/ref name (design D16); the branch name stands in only for a branch
 *     whose tip carries no {@code task.json} to read it from
 * @param stage the stage name the task is positioned at, or {@code null} at the explicit pipeline
 *     end
 * @param attemptsUsed quality failures burned in the current stage, per {@code state.json}
 * @param outcome the lowerCamel outcome discriminator ({@code completed} / {@code paused} /
 *     {@code escalated} / {@code aborted}), or {@code null} while a visit is in progress
 * @param shape the branch's classified shape; never null
 */
public record TaskListRow(
        String taskId,
        @Nullable String stage,
        int attemptsUsed,
        @Nullable String outcome,
        BranchShape shape) {}
