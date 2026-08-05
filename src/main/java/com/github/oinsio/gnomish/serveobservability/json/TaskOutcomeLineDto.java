package com.github.oinsio.gnomish.serveobservability.json;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The JSON contract's {@code taskOutcome} ledger line (v1, spec.md):
 * {@code version} (always {@code 1}), {@code type} (always {@code
 * "taskOutcome"}), {@code instance}, then the outcome fields (FR10, FR11).
 * Every {@code null} field renders as JSON {@code null} — see
 * {@link LedgerJson}.
 *
 * <p>Implements FR10, FR11 conventions of add-serve-observability.
 *
 * @param version the contract version; always {@code 1}
 * @param type the line-type discriminator; always {@code "taskOutcome"}
 * @param instance the writing process's identity
 * @param taskId the tracker's original task id
 * @param outcome {@code delivered | awaitingHuman | aborted | revoked}
 * @param parkReason why the task was parked; present only when {@code outcome} is {@code awaitingHuman}
 * @param stage the pipeline stage the task was in when it finished; null at pipeline end
 * @param attemptsUsed stage attempts consumed
 * @param startedAt ISO-8601 UTC instant the run started
 * @param finishedAt ISO-8601 UTC instant the run finished
 * @param wallMillis wall-clock duration of the run, in milliseconds
 * @param tokensByModel token usage keyed by resolved model id
 */
public record TaskOutcomeLineDto(
        int version,
        String type,
        InstanceDto instance,
        String taskId,
        String outcome,
        @Nullable String parkReason,
        @Nullable String stage,
        int attemptsUsed,
        String startedAt,
        String finishedAt,
        long wallMillis,
        Map<String, TokenUsageDto> tokensByModel) {}
