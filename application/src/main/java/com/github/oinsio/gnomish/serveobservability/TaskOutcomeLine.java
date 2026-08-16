package com.github.oinsio.gnomish.serveobservability;

import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A ledger {@code taskOutcome} line: written for every terminal slot result
 * that carries a final state — {@code delivered | awaitingHuman | aborted |
 * revoked} (design D6). {@code EmptyQueue}/{@code Skipped} results produce
 * no line at all (FR11) — there is no {@link TaskOutcomeLine} for them,
 * rather than one with an unmapped {@link TaskOutcome}.
 *
 * <p>{@code parkReason} carries {@link
 * com.github.oinsio.gnomish.app.take.TakeResult.AwaitingHuman#reason()}
 * verbatim when {@code outcome} is {@link TaskOutcome#AWAITING_HUMAN}, and
 * is null otherwise — reused directly rather than duplicated, since it is
 * exactly the data already available at the write point (design D6,
 * {@code TakeSlotRunner}, task group 4).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR11 of add-serve-observability.
 *
 * @param instance the writing process's identity; never null
 * @param taskId the tracker's original task id; never blank
 * @param outcome which terminal {@link TakeResult} variant produced this line; never null
 * @param parkReason why the task was parked; non-null iff {@code outcome} is
 *     {@link TaskOutcome#AWAITING_HUMAN}
 * @param stage the pipeline stage the task was in when it finished; null at
 *     pipeline end
 * @param attemptsUsed stage attempts consumed; never negative
 * @param startedAt when the run started; never null
 * @param finishedAt when the run finished; never null
 * @param wallMillis wall-clock duration of the run, in milliseconds; never negative
 * @param tokensByModel token usage keyed by resolved model id; possibly empty when unreported
 */
public record TaskOutcomeLine(
        InstanceInfo instance,
        String taskId,
        TaskOutcome outcome,
        @Nullable ParkReason parkReason,
        @Nullable String stage,
        int attemptsUsed,
        Instant startedAt,
        Instant finishedAt,
        long wallMillis,
        Map<String, LedgerTokenUsage> tokensByModel)
        implements LedgerLine {

    public TaskOutcomeLine {
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("TaskOutcomeLine.taskId must not be blank");
        }
        if ((outcome == TaskOutcome.AWAITING_HUMAN) != (parkReason != null)) {
            throw new IllegalArgumentException("TaskOutcomeLine.parkReason must be set iff outcome is AWAITING_HUMAN");
        }
        if (attemptsUsed < 0) {
            throw new IllegalArgumentException("TaskOutcomeLine.attemptsUsed must not be negative");
        }
        if (wallMillis < 0) {
            throw new IllegalArgumentException("TaskOutcomeLine.wallMillis must not be negative");
        }
        tokensByModel = Map.copyOf(tokensByModel);
    }
}
