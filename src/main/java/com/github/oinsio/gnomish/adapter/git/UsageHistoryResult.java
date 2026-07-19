package com.github.oinsio.gnomish.adapter.git;

import java.util.List;

/**
 * The outcome of {@link UsageHistoryWalker#walk}: either the task branch was located and its
 * {@code state.json} history walked into rows, or no branch exists anywhere for the requested
 * task. Mirrors the {@link BranchStateResult} precedent so {@code usage} and {@code status} give
 * callers the same "not found is not a defect" shape (FR14).
 *
 * <p>Implements FR14 of add-git-workflow.
 */
public sealed interface UsageHistoryResult {

    /**
     * The task branch was found and its {@code state.json} history walked successfully.
     *
     * @param rows every detected round, oldest to newest; possibly empty (a task with only a
     *     {@code task.json} commit and no rounds yet)
     * @param totals the cumulative usage across {@code rows}
     */
    record Found(List<UsageRow> rows, UsageTotals totals) implements UsageHistoryResult {}

    /**
     * No {@code gnomish/<task>} branch exists locally, as a remote-tracking ref, or on {@code
     * origin} — including after the narrow fetch attempt (see {@link BranchLocation.NotFound}).
     */
    record NotFound() implements UsageHistoryResult {}
}
