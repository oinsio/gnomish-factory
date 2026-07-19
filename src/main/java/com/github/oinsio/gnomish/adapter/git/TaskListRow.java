package com.github.oinsio.gnomish.adapter.git;

import org.jspecify.annotations.Nullable;

/**
 * One row of {@code gnomish status}' list mode (FR13): a task branch's tip summary — just enough
 * for the table, not a full {@link com.github.oinsio.gnomish.status.StatusReport}. Produced by
 * {@link TaskBranchLister}, one row per deduplicated task.
 *
 * <p>Implements FR13 of add-git-workflow.
 *
 * @param taskId the authoritative taskId, read from {@code task.json} at the branch tip — never
 *     parsed from the branch/ref name (design D16)
 * @param stage the stage name the task is positioned at, or {@code null} at the explicit pipeline
 *     end
 * @param attemptsUsed quality failures burned in the current stage, per {@code state.json}
 * @param outcome the lowerCamel outcome discriminator ({@code completed} / {@code paused} /
 *     {@code escalated} / {@code aborted}), or {@code null} while a visit is in progress
 */
public record TaskListRow(
        String taskId,
        @Nullable String stage,
        int attemptsUsed,
        @Nullable String outcome) {}
