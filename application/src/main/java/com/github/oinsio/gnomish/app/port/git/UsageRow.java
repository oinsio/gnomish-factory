package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.domain.engine.AttemptRecord;
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;

/**
 * One reconstructed round of {@code gnomish usage} (FR14, NFR-C1 of add-git-workflow): the stage
 * visit it belongs to plus the full {@link AttemptRecord} that appeared for it, carried verbatim
 * rather than re-projected — round number, result, wall time, {@code tokensByModel}, tool
 * aggregates, and per-vote judge usage are all reachable from {@code attempt}, so the text-mode
 * summary (task 5.6) and the {@code --json} full-granularity rendering can both be built from the
 * same row without a second git-history walk.
 *
 * <p>The round is carried as the domain {@link AttemptRecord}, not as the state-file DTO it was
 * parsed from: {@code state.json}'s shape is an adapter contract, and a port-level row typed in it
 * would bind {@code application} to the git adapter's wire format (FR12b, design D12 of
 * split-into-modules). The git adapter maps DTO → {@code AttemptRecord} on the way out, the same
 * mapping the resume path already performs.
 *
 * <p>Produced by the git-side usage-history walk, one row per detected round in chronological
 * (oldest→newest) order — see that walker's javadoc for exactly when a commit yields a row.
 *
 * <p>Implements FR14, NFR-C1 of add-git-workflow.
 *
 * @param stage the stage name this round's attempt was recorded under, i.e. the {@code
 *     state.json} {@code position} at the time this round was appended
 * @param attempt the round's full recorded detail as a domain attempt record
 */
public record UsageRow(String stage, AttemptRecord attempt) {

    /** This row's executor usage, the same field {@link AttemptRecord#executorUsage()} carries. */
    public ExecutorUsage executorUsage() {
        return attempt.executorUsage();
    }
}
