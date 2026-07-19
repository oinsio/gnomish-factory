package com.github.oinsio.gnomish.adapter.git;

/**
 * The closed set of {@code TaskRepository} lifecycle writes that get a service commit
 * message (design D14): {@link #STARTED} for {@code createTask}, {@link #RESUMED} for
 * {@code appendDecision} (which also resets {@code outcome} to null in the same commit —
 * see {@code TaskRepository#appendDecision}), and one variant per {@code TaskOutcome}
 * written by {@code recordOutcome} — {@link #COMPLETED}, {@link #PAUSED}, {@link
 * #ESCALATED}, {@link #ABORTED}.
 *
 * <p>Deliberately closed rather than a free-text event name: {@link
 * ServiceCommitMessages#taskEvent} switches over every constant exhaustively, so adding
 * a seventh lifecycle write forces a compile error here instead of producing
 * inconsistent wording later.
 *
 * <p>Implements FR2 of add-git-workflow (design D14).
 */
public enum TaskLifecycleEvent {
    STARTED,
    RESUMED,
    COMPLETED,
    PAUSED,
    ESCALATED,
    ABORTED
}
