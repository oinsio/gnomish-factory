package com.github.oinsio.gnomish.app.port.tracker;

/**
 * Why a task was parked into {@link TrackerTaskState.AwaitingHuman}: {@link
 * #ESCALATION} — a human decision is needed ({@code AttemptsExhausted} or
 * {@code DecisionNeeded} engine escalation kinds); {@link #CHECKPOINT} — a
 * {@code manual} pipeline pause; {@link #INFRA} — an environment or pipeline
 * problem needs a fix before retrying ({@code CannotVerify}, {@code
 * CannotExecute}, {@code PipelineMismatch} escalation kinds, or the K-abort
 * fuse). The split matters for resume: an {@code ESCALATION} or {@code
 * CHECKPOINT} park resumes on the human's decision text (or the return itself
 * for a checkpoint); an {@code INFRA} park resumes as a bare retry with no
 * decision text required (design D3).
 *
 * <p>Implements FR2 of add-tracker-port.
 */
public enum ParkReason {
    /** A human decision is required before the task can proceed. */
    ESCALATION,
    /** A {@code manual} pipeline checkpoint paused the run. */
    CHECKPOINT,
    /** An environment or pipeline problem needs a human fix, no decision text. */
    INFRA
}
