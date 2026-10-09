package com.github.oinsio.gnomish.app.port.git;

/**
 * The closed set of {@code TaskRepository} lifecycle writes that get a service commit
 * message (design D14): {@link #STARTED} for {@code createTask}, {@link #RESUMED} for
 * {@code appendDecision} (which also resets {@code outcome} to null in the same commit —
 * see {@code TaskRepository#appendDecision}) and for {@code resumeFrom} (the resumed write, which
 * consumes the outcome without a decision, FR7 of make-checkpoint-gate-durable — both begin a
 * resumed visit, so they share the label), {@link #APPROVED} for {@code approveCheckpoint}
 * (the write that opens a {@code manual} gate, FR3 of make-checkpoint-gate-durable), and one
 * variant per {@code TaskOutcome}
 * written by {@code recordOutcome} — {@link #COMPLETED}, {@link #PAUSED}, {@link
 * #ESCALATED}, {@link #ABORTED}.
 *
 * <p>Deliberately closed rather than a free-text event name: {@code
 * ServiceCommitMessages#taskEvent} switches over every constant exhaustively, so adding
 * another lifecycle write forces a compile error here instead of producing
 * inconsistent wording later.
 *
 * <p>Implements FR2 of add-git-workflow (design D14); FR3, FR7 of make-checkpoint-gate-durable.
 */
public enum TaskLifecycleEvent {
    STARTED,
    RESUMED,
    APPROVED,
    COMPLETED,
    PAUSED,
    ESCALATED,
    ABORTED
}
