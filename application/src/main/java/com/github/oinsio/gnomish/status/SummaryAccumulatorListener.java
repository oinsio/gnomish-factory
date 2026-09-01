package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.domain.engine.EngineEvent;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * The manual-run end of the canonical task summary (design D3, FR3 of
 * harden-logging-observability): an {@link EngineEventListener} that times the run from {@link
 * EngineEvent.RunStarted} and emits the one summary line on {@link EngineEvent.TaskFinished}.
 *
 * <p>A manual run has no terminal {@code TakeResult} to map — it has no claim, no slot and no
 * tracker write, and it ends by returning or by throwing, differently per mode (in-place, git,
 * container, resume). What it does have is the engine's own run bookend, which fires for every
 * terminal outcome including pre-flight escalations that never reached a stage. So the bookend is
 * the assembly point: one listener covers all five manual entry points without any of them
 * learning about the log plane.
 *
 * <p>What it accumulates is only the wall time. Stage, attempts and token totals are read off the
 * outcome's {@code finalState}, which already carries the whole-task cumulative figures — summing
 * the event stream's per-round usage in parallel would be a second accounting of the same numbers,
 * and a second accounting is exactly how two planes come to disagree.
 *
 * <p>Kept in sync with {@link com.github.oinsio.gnomish.app.take.TaskSummaryAssembler}: both must
 * populate the full {@link TaskSummary} vocabulary for every terminal outcome family, so the same
 * task described by a manual run and by a serve slot reads the same. {@link
 * TaskSummary.Outcome#REVOKED} is the one family this end cannot produce and the other end can — a
 * manual run holds no claim, so nothing can revoke it (design D8).
 *
 * <p>Wall time comes from {@link System#nanoTime()}: a duration must not be affected by a
 * wall-clock adjustment landing mid-run, and the same monotonic source is what the serve/take end
 * measures with.
 *
 * <p>Every branch here is a plain arithmetic or logging step with no I/O of its own, so — like
 * {@link LoggingEventListener} — this class needs no defensive exception handling to satisfy the
 * port's "never throw past {@code onEvent}" contract.
 *
 * <p>Implements FR3 of harden-logging-observability.
 */
public final class SummaryAccumulatorListener implements EngineEventListener {

    private long startedNanos = System.nanoTime();

    /**
     * Starts the clock on the run bookend and emits the summary on the closing one; every other
     * event is not this listener's business.
     *
     * @param event the event that just occurred; never null
     */
    @Override
    public void onEvent(EngineEvent event) {
        switch (event) {
            case EngineEvent.RunStarted _ -> startedNanos = System.nanoTime();
            case EngineEvent.TaskFinished finished -> AnchorLog.taskSummary(summaryOf(finished.outcome()));
            case EngineEvent.AttemptStarted _,
                    EngineEvent.ExecutionFinished _,
                    EngineEvent.CheckStarted _,
                    EngineEvent.CheckFinished _,
                    EngineEvent.AttemptFinished _ -> {}
        }
    }

    /**
     * Maps the engine's terminal outcome onto the summary vocabulary. A pause and an escalation
     * are both parks awaiting a human, distinguished by the same reason names the tracker port
     * uses on the other end, so the two assemblers word an identical situation identically.
     */
    private TaskSummary summaryOf(TaskOutcome outcome) {
        Duration wall = Duration.ofNanos(System.nanoTime() - startedNanos);
        return switch (outcome) {
            case TaskOutcome.Completed completed ->
                summary(TaskSummary.Outcome.DELIVERED, null, completed.finalState(), wall);
            case TaskOutcome.Paused paused ->
                summary(TaskSummary.Outcome.AWAITING_HUMAN, "CHECKPOINT", paused.finalState(), wall);
            case TaskOutcome.Escalated escalated ->
                summary(TaskSummary.Outcome.AWAITING_HUMAN, "ESCALATION", escalated.finalState(), wall);
            case TaskOutcome.Aborted aborted -> summary(TaskSummary.Outcome.ABORTED, null, aborted.finalState(), wall);
        };
    }

    private static TaskSummary summary(
            TaskSummary.Outcome outcome, @Nullable String parkReason, TaskState finalState, Duration wall) {
        return new TaskSummary(
                outcome,
                parkReason,
                stageOf(finalState.position()),
                finalState.attemptsUsed(),
                wall,
                finalState.totals().tokensByModel());
    }

    private static @Nullable String stageOf(Position position) {
        return switch (position) {
            case Position.AtStage atStage -> atStage.name();
            case Position.PipelineEnd _ -> null;
        };
    }
}
