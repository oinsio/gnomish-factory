package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.StateAttemptDto;
import com.github.oinsio.gnomish.adapter.git.state.StateUsageDto;

/**
 * One reconstructed round of {@code gnomish usage} (FR14, NFR-C1 of add-git-workflow): the stage
 * visit it belongs to plus the full {@link StateAttemptDto} that appeared for it, carried
 * verbatim rather than re-projected — round number, result, wall time, {@code tokensByModel},
 * {@code byTool}, and per-vote judge usage are all reachable from {@code attempt}, so the
 * text-mode summary (task 5.6) and the {@code --json} full-granularity rendering can both be
 * built from the same row without a second git-history walk.
 *
 * <p>Produced by {@link UsageHistoryWalker#walk}, one row per detected round in chronological
 * (oldest→newest) order — see that class's javadoc for exactly when a commit yields a row.
 *
 * <p>Implements FR14, NFR-C1 of add-git-workflow.
 *
 * @param stage the stage name this round's attempt was recorded under, i.e. the {@code
 *     state.json} {@code position} at the time this round was appended
 * @param attempt the round's full recorded detail, straight from the state-file DTO tree
 */
public record UsageRow(String stage, StateAttemptDto attempt) {

    /** This row's executor usage, the same field {@link StateAttemptDto#executorUsage()} carries. */
    public StateUsageDto executorUsage() {
        return attempt.executorUsage();
    }
}
