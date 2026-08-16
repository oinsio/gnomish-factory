package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import org.jspecify.annotations.Nullable;

/**
 * The mutable holder a {@link StatusEventListener} writes into and a status render
 * reads from: the current {@link TaskState}, the current stage's resolved attempt
 * limit, and the current {@link LiveActivity}, updated in place as {@code
 * EngineEvent}s arrive (design D7 of add-manual-run).
 *
 * <p>The holder does not own a {@link TaskContext}: identity ({@code taskId},
 * {@code title}, {@code body}, {@code decisions}) is task-level and does not evolve
 * during a run, unlike {@code TaskState}/{@code LiveActivity} which change on every
 * event. Keeping it out of the holder means {@link #current(TaskContext)} takes the
 * context from whichever caller already holds it (the runner, a status command) —
 * there is exactly one place that constructs it, so duplicating it into the holder
 * would only add a second, potentially stale copy.
 *
 * <p>{@code attemptLimit} is NOT derivable from {@code TaskContext} or {@code
 * TaskState} alone — it comes from the pipeline's stage configuration ({@code
 * StageDefinition.limits()}) — so the holder must be told it explicitly via {@link
 * #updateAttemptLimit(int)} whenever the position advances to a new stage. Sections
 * 7.x (the runner) are not built yet; this method exists for the runner to call once
 * it resolves each stage's {@code AutonomyLimits}. Until called, the holder reports
 * whatever {@code attemptLimit} it was constructed with.
 *
 * <p>Reads and writes are synchronized: this is a single-task manual-run process, not
 * a high-concurrency service, so a coarse {@code synchronized} on each accessor is
 * simple and sufficient rather than a lock-free structure built for contention this
 * process never sees.
 *
 * <p>Implements FR10, D7 of add-manual-run.
 */
public final class StatusSnapshotHolder {

    private TaskState state;
    private int attemptLimit;
    private LiveActivity activity;

    /**
     * Creates a holder starting at {@code initialState}, {@code initialAttemptLimit},
     * and idle activity — the natural starting point before any engine event has
     * arrived.
     *
     * @param initialState the task's state before any event is observed; never null
     * @param initialAttemptLimit the resolved attempt limit of the stage {@code
     *     initialState} is positioned at
     */
    public StatusSnapshotHolder(TaskState initialState, int initialAttemptLimit) {
        this.state = initialState;
        this.attemptLimit = initialAttemptLimit;
        this.activity = LiveActivity.idle();
    }

    /**
     * Replaces the held {@link TaskState}, called by the listener on
     * {@code AttemptFinished}.
     *
     * @param newState the state after the finished attempt; never null
     */
    public synchronized void updateState(TaskState newState) {
        this.state = newState;
    }

    /**
     * Replaces the held attempt limit, called by the runner (section 7.x) whenever
     * the task's position advances to a new stage — the resolved limit is pipeline
     * configuration, not derivable from {@code TaskState} alone.
     *
     * @param newAttemptLimit the resolved attempt limit of the stage now current
     */
    public synchronized void updateAttemptLimit(int newAttemptLimit) {
        this.attemptLimit = newAttemptLimit;
    }

    /**
     * Replaces the held activity, preserving whatever escalation report and outcome
     * are already held — activity, escalation and outcome are updated by different
     * events and must not clobber each other.
     *
     * @param newActivity the new live activity, or {@code null} for idle
     */
    public synchronized void updateActivity(@Nullable Activity newActivity) {
        this.activity = new LiveActivity(newActivity, this.activity.lastEscalation(), this.activity.outcome());
    }

    /**
     * Records the most recent escalation report and resets activity to idle —
     * called on a {@code TaskFinished} carrying {@code TaskOutcome.Escalated}, at
     * which point the run has stopped waiting on anything engine-side. Also records
     * the equivalent {@link Outcome.Escalated}, mirroring {@link
     * #recordOutcome(Outcome)}.
     *
     * @param report the escalation report observed live; never null
     */
    public synchronized void recordEscalation(EscalationReport report) {
        this.activity = new LiveActivity(null, report, new Outcome.Escalated(report));
    }

    /**
     * Records the terminal {@link Outcome} of the run and resets activity to idle —
     * called on any {@code TaskFinished} event, for all four outcome kinds. For an
     * {@code Escalated} outcome, prefer {@link #recordEscalation(EscalationReport)},
     * which records both the escalation and its equivalent outcome in one call.
     *
     * @param newOutcome the terminal outcome observed live; never null
     */
    public synchronized void recordOutcome(Outcome newOutcome) {
        this.activity = new LiveActivity(null, this.activity.lastEscalation(), newOutcome);
    }

    /**
     * Builds a fresh {@link StatusReport} from the currently held state, attempt
     * limit and activity plus the caller-supplied {@code context} — a pure read
     * with no side effects.
     *
     * @param context the task's identity and human decisions; never null
     * @return the assembled report reflecting the snapshot at call time
     */
    public synchronized StatusReport current(TaskContext context) {
        return StatusReport.build(context, state, attemptLimit, activity);
    }

    /**
     * The currently held {@link TaskState}, for callers that need the state alone
     * rather than a full report.
     */
    public synchronized TaskState state() {
        return state;
    }

    /**
     * The currently held attempt limit, for callers that need it alone rather than
     * a full report.
     */
    public synchronized int attemptLimit() {
        return attemptLimit;
    }

    /**
     * The currently held {@link LiveActivity}, for callers that need the activity
     * alone rather than a full report.
     */
    public synchronized LiveActivity activity() {
        return activity;
    }

    /** The currently held escalation report, or {@code null} when none has occurred. */
    public synchronized @Nullable EscalationReport lastEscalation() {
        return activity.lastEscalation();
    }

    /** The currently held terminal outcome, or {@code null} while the run is in progress. */
    public synchronized @Nullable Outcome outcome() {
        return activity.outcome();
    }
}
