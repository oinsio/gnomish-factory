package com.github.oinsio.gnomish.adapter.git.state;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code state.json} contract's per-attempt shape carried under {@code
 * attempts}: {@code round}, {@code result}, {@code startedAt}, {@code checks},
 * {@code executorUsage}, {@code judgeUsage} — mirrors {@code status.json}'s
 * {@code AttemptDto} field-for-field (design D5), as a distinct class in this
 * package, and mirrors the domain's {@link
 * com.github.oinsio.gnomish.domain.engine.AttemptRecord} 1:1 so this DTO round-trips
 * fully back into the domain (unlike {@code task.json}'s DTOs).
 *
 * <p>{@code denials} is additive under contract v1 (D5 of
 * fix-denial-report-attachment): a state file written before the field existed
 * binds the component to null, which the canonical constructor normalizes to
 * empty, so every pre-existing document stays readable.
 *
 * <p>Implements FR3, FR4 of add-git-workflow; FR4 of fix-denial-report-attachment.
 *
 * @param round the round's sequence number within the current stage
 * @param result the lowerCamel result classification ({@code passed} / {@code
 *     qualityFailure} / {@code cannotVerify} / {@code decisionNeeded})
 * @param startedAt ISO-8601 UTC instant the round began
 * @param checks the verify results produced this round; possibly empty
 * @param denials the egress denials recorded during this round; possibly empty,
 *     and absent in documents written before the field existed
 * @param executorUsage the round's aggregate executor usage
 * @param judgeUsage the round's per-vote judge token usage
 */
public record StateAttemptDto(
        int round,
        String result,
        String startedAt,
        List<StateCheckDto> checks,
        List<StateFindingDto> denials,
        StateUsageDto executorUsage,
        StateJudgeUsageDto judgeUsage) {

    public StateAttemptDto {
        denials = absentAsEmpty(denials);
    }

    /**
     * An absent {@code denials} field reads as an empty list (FR4 of
     * fix-denial-report-attachment): the field is additive under contract v1, so a
     * state file written before it existed must keep parsing. Kept as an explicit
     * static method rather than inline in the compact constructor — PIT's record
     * filter suppresses mutations inside a record's canonical constructor, which
     * would exempt this default from the mutation gate.
     */
    private static List<StateFindingDto> absentAsEmpty(@Nullable List<StateFindingDto> denials) {
        return denials == null ? List.of() : List.copyOf(denials);
    }
}
