package com.github.oinsio.gnomish.status.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.oinsio.gnomish.executorcontract.usagewire.ParticipantUsageDto;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The JSON contract's per-attempt shape carried under {@code currentStage.attempts}:
 * the round's identity {@code taskId}, {@code stage} and {@code roundToken}, then {@code round}, {@code result}, {@code stop}, {@code startedAt}, {@code checks}, {@code denials},
 * {@code usage} — one participant entry per executor invocation of the round, spelled through the
 * one usage wire vocabulary (spec.md; FR15, FR16 of define-executor-contract).
 *
 * <p>{@code denials} carries the egress denials of the round, in the same finding
 * shape a failed check's {@code findings} use — additive under contract v1 and
 * always present, empty when the round denied nothing (UX2 of
 * fix-denial-report-attachment). It is observability only and derives nothing:
 * {@code result} reads the same with or without it.
 *
 * <p>{@code stop} carries the stop the round raised — the content its escalation is built from
 * — and is always present, {@code none} for a round that raised none (FR10 of
 * make-checkpoint-gate-durable, a pre-release amendment of contract v1).
 *
 * <p>The identity is the record's own, never inferred from the position (FR14 of
 * define-executor-contract, a pre-release amendment of contract v1): {@code roundToken} is the
 * commit the round opened on in a branch mode and is omitted for a round with none (in-place).
 *
 * <p>Implements FR11, M3 of add-manual-run; FR4 of fix-denial-report-attachment; FR10 of
 * make-checkpoint-gate-durable; FR14 of define-executor-contract.
 *
 * @param taskId the task the round belongs to
 * @param stage the stage that ran the round
 * @param roundToken the commit the round opened on; omitted when the round has none
 * @param round the round's sequence number within the current stage
 * @param result the lowerCamel result classification ({@code passed} / {@code
 *     qualityFailure} / {@code cannotVerify} / {@code decisionNeeded})
 * @param stop the stop the round raised; {@code none} when it raised none
 * @param startedAt ISO-8601 UTC instant the round began
 * @param checks the verify results produced this round; possibly empty
 * @param denials the egress denials recorded during this round; possibly empty
 * @param usage the round's participant entries
 */
public record AttemptDto(
        String taskId,
        String stage,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String roundToken,
        int round,
        String result,
        StopDto stop,
        String startedAt,
        List<CheckDto> checks,
        List<FindingDto> denials,
        List<ParticipantUsageDto> usage) {}
