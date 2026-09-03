package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.status.TaskSummary;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * The serve/take end of the canonical task summary (design D3, FR3 of
 * harden-logging-observability): the pure mapping from a terminal {@link TakeResult} to the facts
 * {@code AnchorLog.taskSummary} renders.
 *
 * <p>Only the four variants carrying a {@code finalState} map to a summary. {@link
 * TakeResult.EmptyQueue} and {@link TakeResult.Skipped} map to {@code null} — no engine run
 * happened, so there is no task to summarize, the same boundary {@code
 * TaskOutcomeLineAssembler} draws for the ledger.
 *
 * <p>This is deliberately the <em>shared</em> fact extraction rather than a second one:
 * {@code serveobservability.TaskOutcomeLineAssembler} builds the ledger's {@code taskOutcome}
 * line from the summary this returns, so the log plane and the machine plane cannot come to
 * disagree about a task's stage, attempts or token totals (the spec's "the same summary facts
 * that feed the ledger where a ledger line exists").
 *
 * <p>Kept in sync with {@link com.github.oinsio.gnomish.status.SummaryAccumulatorListener}: both
 * must populate the full {@link TaskSummary} vocabulary for every terminal outcome family, so a
 * manual run and a serve slot describe the same task the same way. The two are a declared pair
 * rather than one abstraction because their fact sources genuinely differ — a terminal result
 * here, an engine event stream there (design D8).
 *
 * <p>Stateless: a pure function with no fields.
 *
 * <p>Implements FR3, D3 of harden-logging-observability.
 */
public final class TaskSummaryAssembler {

    private TaskSummaryAssembler() {}

    /**
     * Assembles the summary facts for {@code result}, or returns {@code null} for the two variants
     * that describe a run that never happened.
     *
     * @param result the terminal result of a claimed-and-worked task; never null
     * @param wall how long the work took; never negative
     * @return the summary facts, or {@code null} for {@code EmptyQueue}/{@code Skipped}
     */
    public static @Nullable TaskSummary assemble(TakeResult result, Duration wall) {
        return switch (result) {
            case TakeResult.Delivered delivered ->
                summary(TaskSummary.Outcome.DELIVERED, null, delivered.finalState(), wall);
            case TakeResult.AwaitingHuman awaitingHuman ->
                summary(
                        TaskSummary.Outcome.AWAITING_HUMAN,
                        awaitingHuman.reason().name(),
                        awaitingHuman.finalState(),
                        wall);
            case TakeResult.Aborted aborted -> summary(TaskSummary.Outcome.ABORTED, null, aborted.finalState(), wall);
            case TakeResult.Revoked revoked -> summary(TaskSummary.Outcome.REVOKED, null, revoked.finalState(), wall);
            case TakeResult.EmptyQueue ignored -> null;
            case TakeResult.Skipped ignored -> null;
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
            case Position.PipelineEnd ignored -> null;
        };
    }
}
