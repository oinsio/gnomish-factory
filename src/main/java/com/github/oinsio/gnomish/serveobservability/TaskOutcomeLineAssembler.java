package com.github.oinsio.gnomish.serveobservability;

import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.TokenUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The pure mapping from a terminal {@link TakeResult} to the ledger's {@code taskOutcome} line
 * (design D6, FR11): only the four variants carrying a {@code finalState} —
 * {@link TakeResult.Delivered}, {@link TakeResult.AwaitingHuman}, {@link TakeResult.Aborted},
 * {@link TakeResult.Revoked} — map to a {@link TaskOutcomeLine}; {@link TakeResult.EmptyQueue}
 * and {@link TakeResult.Skipped} map to {@code null} since no engine run happened, so no line is
 * written for them ("engine run happened iff spend happened iff line exists", design D6).
 *
 * <p>{@code stage} and {@code attemptsUsed} come straight off {@code finalState}, exactly like
 * {@code StatusReport} builds its own outcome text from the same fields (design D6): {@link
 * Position.AtStage#name()} becomes {@code stage}, {@link Position.PipelineEnd} becomes a {@code
 * null} stage; {@link TaskState#attemptsUsed()} carries across verbatim. {@code tokensByModel}
 * reuses the whole-task cumulative total already carried on {@code finalState} (design D6's "raw
 * material already exists" — per-task token totals in status-report v1) rather than collecting
 * anything new, converted field-for-field from {@link TokenUsage} to this package's {@link
 * LedgerTokenUsage} (mirroring {@link LedgerTokenUsage}'s own package-local-copy rationale).
 *
 * <p>Stateless: a pure function with no fields, following the module's assembler convention
 * (e.g. {@link TrackerHealthAssembler}, {@link SlotEntryAssembler}).
 *
 * <p>Implements FR11, D6 of add-serve-observability.
 */
public final class TaskOutcomeLineAssembler {

    private TaskOutcomeLineAssembler() {}

    /**
     * Assembles a {@link TaskOutcomeLine} for {@code result}, or returns {@code null} for {@link
     * TakeResult.EmptyQueue}/{@link TakeResult.Skipped} — the only variants with no {@code
     * finalState} to derive a line from.
     *
     * @param instance the writing process's identity; never null
     * @param taskId the tracker's original task id; never blank
     * @param result the slot's terminal result; never null
     * @param startedAt when the run started; never null
     * @param finishedAt when the run finished; never null (must not precede {@code startedAt})
     * @return the assembled line, or {@code null} for {@code EmptyQueue}/{@code Skipped}
     */
    public static @Nullable TaskOutcomeLine assemble(
            InstanceInfo instance, String taskId, TakeResult result, Instant startedAt, Instant finishedAt) {
        return switch (result) {
            case TakeResult.Delivered delivered ->
                line(instance, taskId, TaskOutcome.DELIVERED, null, delivered.finalState(), startedAt, finishedAt);
            case TakeResult.AwaitingHuman awaitingHuman ->
                line(
                        instance,
                        taskId,
                        TaskOutcome.AWAITING_HUMAN,
                        awaitingHuman.reason(),
                        awaitingHuman.finalState(),
                        startedAt,
                        finishedAt);
            case TakeResult.Aborted aborted ->
                line(instance, taskId, TaskOutcome.ABORTED, null, aborted.finalState(), startedAt, finishedAt);
            case TakeResult.Revoked revoked ->
                line(instance, taskId, TaskOutcome.REVOKED, null, revoked.finalState(), startedAt, finishedAt);
            case TakeResult.EmptyQueue emptyQueue -> null;
            case TakeResult.Skipped skipped -> null;
        };
    }

    private static TaskOutcomeLine line(
            InstanceInfo instance,
            String taskId,
            TaskOutcome outcome,
            @Nullable ParkReason parkReason,
            TaskState finalState,
            Instant startedAt,
            Instant finishedAt) {
        return new TaskOutcomeLine(
                instance,
                taskId,
                outcome,
                parkReason,
                stageOf(finalState.position()),
                finalState.attemptsUsed(),
                startedAt,
                finishedAt,
                Duration.between(startedAt, finishedAt).toMillis(),
                toLedgerTokens(finalState.totals().tokensByModel()));
    }

    private static @Nullable String stageOf(Position position) {
        return switch (position) {
            case Position.AtStage atStage -> atStage.name();
            case Position.PipelineEnd pipelineEnd -> null;
        };
    }

    private static Map<String, LedgerTokenUsage> toLedgerTokens(Map<String, TokenUsage> tokensByModel) {
        Map<String, LedgerTokenUsage> mapped = new LinkedHashMap<>();
        tokensByModel.forEach((model, usage) -> mapped.put(model, LedgerTokenUsage.of(usage)));
        return mapped;
    }
}
