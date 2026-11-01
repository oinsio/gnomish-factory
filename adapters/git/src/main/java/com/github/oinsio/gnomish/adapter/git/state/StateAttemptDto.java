package com.github.oinsio.gnomish.adapter.git.state;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.github.oinsio.gnomish.executorcontract.usagewire.ParticipantUsageDto;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code state.json} contract's per-attempt shape carried under {@code
 * attempts}: the round's identity {@code taskId}, {@code stage}, {@code roundToken}, then {@code round}, {@code result}, {@code startedAt}, {@code checks},
 * {@code denials}, {@code usage}, {@code stop} — mirrors {@code status.json}'s
 * {@code AttemptDto} field-for-field (design D5), as a distinct class in this
 * package, and mirrors the domain's {@link
 * com.github.oinsio.gnomish.domain.engine.AttemptRecord} 1:1 so this DTO round-trips
 * fully back into the domain (unlike {@code task.json}'s DTOs).
 *
 * <p>{@code denials} is additive under contract v1 (D5 of
 * fix-denial-report-attachment): a state file written before the field existed
 * binds the component to null, which the canonical constructor normalizes to
 * empty, so every pre-existing document stays readable. Each entry additionally
 * carries the identity its denial source assigned it, itself additive and absent
 * on entries written before it existed (FR7 of fix-denial-attribution-durability).
 *
 * <p>{@code stop} is the stop the round raised ({@link StateStopDto}), written on every attempt
 * and additive under contract v1 (design D5 of make-checkpoint-gate-durable): a state file written
 * before the field existed binds it to null, which the canonical constructor normalizes to {@code
 * none}.
 *
 * <p>The identity (FR14, FR21 of define-executor-contract, design D13) is a pre-release amendment under
 * version 1: {@code taskId} and {@code stage} are required, so a document written before them is
 * refused as malformed rather than filled in from the position — a reader never infers a round's
 * identity; {@code roundToken} is the tip the round opened on, {@code null} only for a round with
 * none (an in-place round, which no state file records).
 *
 * <p>{@code usage} is the round's participant list, one entry per executor invocation, in the one
 * usage wire vocabulary the status and usage reports spell too ({@link ParticipantUsageDto}; FR15,
 * FR16 of define-executor-contract): a pre-release amendment under version 1 that replaced the
 * role-named {@code executorUsage} and {@code judgeUsage} slots. An attempt without the field reads
 * as an empty list, never as null.
 *
 * <p>Implements FR3, FR4 of add-git-workflow; FR4 of fix-denial-report-attachment; FR10 of
 * make-checkpoint-gate-durable; FR14, FR15, FR16, FR21 of define-executor-contract.
 *
 * @param taskId the task the round belongs to; required
 * @param stage the stage that ran the round; required
 * @param roundToken the commit the round opened on; null for a round with no token
 * @param round the round's sequence number within the current stage
 * @param result the lowerCamel result classification ({@code passed} / {@code
 *     qualityFailure} / {@code cannotVerify} / {@code decisionNeeded} / {@code budgetExhausted})
 * @param startedAt ISO-8601 UTC instant the round began
 * @param checks the verify results produced this round; possibly empty
 * @param denials the egress denials recorded during this round; possibly empty,
 *     and absent in documents written before the field existed
 * @param usage the round's participant entries
 * @param stop the stop the round raised; {@code none} when it raised none or the document
 *     predates the field
 */
public record StateAttemptDto(
        String taskId,
        String stage,
        @Nullable String roundToken,
        int round,
        String result,
        String startedAt,
        List<StateCheckDto> checks,
        List<StateDenialDto> denials,
        @JsonSetter(nulls = Nulls.AS_EMPTY) List<ParticipantUsageDto> usage,
        StateStopDto stop) {

    public StateAttemptDto {
        denials = StateDenialMapper.absentAsEmpty(denials);
        stop = StateStopMapper.absentAsNone(stop);
    }
}
