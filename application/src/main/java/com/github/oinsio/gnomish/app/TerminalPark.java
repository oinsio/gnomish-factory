package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.engine.TaskOutcome;

/**
 * The one place that answers "is this terminal outcome a park?" — an {@code Escalated} or {@code
 * Paused} run, the two outcomes that hand the task to a human and therefore record a {@code
 * pendingTrackerWrite} marker the tracker write later confirms.
 *
 * <p>Two replication decisions read the same answer and must never drift apart: the pre-park
 * delivery fence runs for exactly these outcomes ({@link TakeEngineExecution}, FR4, NG6 of
 * fix-lifecycle-push), and the terminal-boundary reconciliation is skipped for exactly these
 * outcomes because that fence already supersedes it ({@link GitOutcomeRecorder}, FR3).
 *
 * <p>Implements FR3, FR4 of fix-lifecycle-push.
 */
final class TerminalPark {

    private TerminalPark() {}

    /**
     * Whether {@code outcome} parks the task for a human.
     *
     * @param outcome the terminal outcome to classify; never null
     * @return {@code true} for {@code Escalated} and {@code Paused}, {@code false} for {@code
     *     Completed} and {@code Aborted}
     */
    static boolean isPark(TaskOutcome outcome) {
        return outcome instanceof TaskOutcome.Escalated || outcome instanceof TaskOutcome.Paused;
    }
}
