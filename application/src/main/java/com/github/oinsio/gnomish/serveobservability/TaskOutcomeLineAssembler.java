package com.github.oinsio.gnomish.serveobservability;

import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.app.take.TaskSummaryAssembler;
import com.github.oinsio.gnomish.domain.engine.TokenUsage;
import com.github.oinsio.gnomish.status.TaskSummary;
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
 * <p>{@code stage}, {@code attemptsUsed} and {@code tokensByModel} are not derived here: they come
 * from {@link TaskSummaryAssembler}, the one extraction of a terminal result's facts, which the
 * log plane's canonical task summary is also rendered from (FR3, design D3 of
 * harden-logging-observability). Sharing the extraction is what keeps the ledger line and the
 * summary line from ever disagreeing about the same task. The token totals are the whole-task
 * cumulative ones already carried on {@code finalState} (design D6's "raw material already exists"
 * — per-task token totals in status-report v1), converted field-for-field from {@link TokenUsage}
 * to this package's {@link LedgerTokenUsage} (mirroring {@link LedgerTokenUsage}'s own
 * package-local-copy rationale).
 *
 * <p>The {@code parkReason} stays this assembler's own read of the result: the ledger records it
 * as the {@link ParkReason} enum the tracker port owns, while the summary — a deliberately neutral
 * value — carries only its name.
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
        Duration wall = Duration.between(startedAt, finishedAt);
        TaskSummary summary = TaskSummaryAssembler.assemble(result, wall);
        if (summary == null) {
            return null;
        }
        return new TaskOutcomeLine(
                instance,
                taskId,
                outcomeOf(summary.outcome()),
                parkReasonOf(result),
                summary.stage(),
                summary.attemptsUsed(),
                startedAt,
                finishedAt,
                wall.toMillis(),
                toLedgerTokens(summary.tokensByModel()));
    }

    /**
     * The ledger's own outcome vocabulary for the summary's — two enums naming the same four
     * families in two planes, exhaustively mapped so a family added to one fails to compile until
     * it is added to the other.
     */
    private static TaskOutcome outcomeOf(TaskSummary.Outcome outcome) {
        return switch (outcome) {
            case DELIVERED -> TaskOutcome.DELIVERED;
            case AWAITING_HUMAN -> TaskOutcome.AWAITING_HUMAN;
            case ABORTED -> TaskOutcome.ABORTED;
            case REVOKED -> TaskOutcome.REVOKED;
        };
    }

    /**
     * The park reason as the typed enum the ledger records, read straight off the result — the one
     * fact the neutral summary carries only by name (see the class javadoc).
     */
    private static @Nullable ParkReason parkReasonOf(TakeResult result) {
        return result instanceof TakeResult.AwaitingHuman awaitingHuman ? awaitingHuman.reason() : null;
    }

    private static Map<String, LedgerTokenUsage> toLedgerTokens(Map<String, TokenUsage> tokensByModel) {
        Map<String, LedgerTokenUsage> mapped = new LinkedHashMap<>();
        tokensByModel.forEach((model, usage) -> mapped.put(model, LedgerTokenUsage.of(usage)));
        return mapped;
    }
}
