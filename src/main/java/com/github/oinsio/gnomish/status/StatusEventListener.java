package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.domain.engine.EngineEvent;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;

/**
 * The {@link EngineEventListener} adapter that keeps a {@link StatusSnapshotHolder}
 * current as the engine's event stream arrives (design D7 of add-manual-run):
 * {@code AttemptFinished} carries the new {@code TaskState} and resets activity to
 * idle — the engine is momentarily quiescent between rounds at that instant, which
 * is exactly the attempt boundary the status-report capability's equivalence
 * requirement relies on: a report built live at that boundary must equal one built
 * fresh from the same {@code (context, state)} with no live signal ({@link
 * LiveActivity#idle()}), and that can only hold if activity is genuinely idle
 * there rather than left over from the last {@code CheckStarted}; {@code
 * AttemptStarted} sets {@link Activity.Executing}; {@code CheckStarted} sets {@link
 * Activity.Verifying} named by its {@link com.github.oinsio.gnomish.domain.engine.CheckRef};
 * a {@code TaskFinished} captures the terminal {@link Outcome} for all four {@code
 * TaskOutcome} kinds, and additionally the {@code EscalationReport} when the outcome
 * is {@code Escalated}.
 *
 * <p>{@code ExecutionFinished} is deliberately a no-op here: it fires the instant
 * execution ends and verification is about to begin, but the first {@code
 * CheckStarted} that follows already sets {@link Activity.Verifying} with the
 * concrete {@code CheckRef} the JSON contract requires — a coarser, checkRef-less
 * "verifying" in between would only be overwritten a moment later.
 *
 * <p>{@code awaitingInput}/{@link Activity.AwaitingInput} is out of scope here — it
 * is driven by {@code DialogConsole} prompt markers, wired by a separate listener
 * (add-manual-run task 6.3), not by any {@code EngineEvent}.
 *
 * <p>Every branch here is a plain field update on the holder with no I/O, so this
 * class naturally satisfies the port's "never throw past {@code onEvent}" contract
 * without defensive exception handling.
 *
 * <p>Implements FR10, FR11, D7 of add-manual-run.
 */
public final class StatusEventListener implements EngineEventListener {

    private final StatusSnapshotHolder holder;
    private final Clock clock;

    /**
     * Wraps {@code holder}, the snapshot this listener keeps current, and {@code
     * clock}, the source of the {@code since} instant stamped on each activity
     * variant this listener sets.
     *
     * @param holder the snapshot holder to update on each event; never null
     * @param clock the engine's injected time source; never null
     */
    public StatusEventListener(StatusSnapshotHolder holder, Clock clock) {
        this.holder = holder;
        this.clock = clock;
    }

    /**
     * Updates the wrapped {@link StatusSnapshotHolder} per the D7 event mapping.
     * {@code RunStarted} and {@code CheckFinished} are no-ops: neither carries a
     * live-activity or state change this task's scope covers.
     *
     * <p>Implements FR10, FR11, D7 of add-manual-run.
     *
     * @param event the event that just occurred; never null
     */
    @Override
    public void onEvent(EngineEvent event) {
        switch (event) {
            case EngineEvent.AttemptStarted ignored -> holder.updateActivity(new Activity.Executing(clock.now()));
            case EngineEvent.CheckStarted started ->
                holder.updateActivity(new Activity.Verifying(started.check(), clock.now()));
            case EngineEvent.AttemptFinished finished -> {
                holder.updateState(finished.newState());
                holder.updateActivity(null);
            }
            case EngineEvent.TaskFinished finished -> onTaskFinished(finished.outcome());
            case EngineEvent.RunStarted ignored -> {
                // No state or activity change in this task's scope (D7).
            }
            case EngineEvent.ExecutionFinished ignored -> {
                // The following CheckStarted sets the concrete Verifying activity (D7).
            }
            case EngineEvent.CheckFinished ignored -> {
                // No state or activity change in this task's scope (D7).
            }
        }
    }

    private void onTaskFinished(TaskOutcome outcome) {
        if (outcome instanceof TaskOutcome.Escalated escalated) {
            holder.recordEscalation(escalated.report());
        } else {
            holder.recordOutcome(Outcome.from(outcome));
        }
    }
}
