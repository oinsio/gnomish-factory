package com.github.oinsio.gnomish.adapter.git.state;

import java.util.List;

/**
 * The {@code state.json} contract's per-attempt shape carried under {@code
 * attempts}: {@code round}, {@code result}, {@code startedAt}, {@code checks},
 * {@code executorUsage}, {@code judgeUsage} — mirrors {@code status.json}'s
 * {@code AttemptDto} field-for-field (design D5), as a distinct class in this
 * package, and mirrors the domain's {@link
 * com.github.oinsio.gnomish.domain.engine.AttemptRecord} 1:1 so this DTO round-trips
 * fully back into the domain (unlike {@code task.json}'s DTOs).
 *
 * <p>Implements FR3, FR4 of add-git-workflow.
 *
 * @param round the round's sequence number within the current stage
 * @param result the lowerCamel result classification ({@code passed} / {@code
 *     qualityFailure} / {@code cannotVerify} / {@code decisionNeeded})
 * @param startedAt ISO-8601 UTC instant the round began
 * @param checks the verify results produced this round; possibly empty
 * @param executorUsage the round's aggregate executor usage
 * @param judgeUsage the round's per-vote judge token usage
 */
public record StateAttemptDto(
        int round,
        String result,
        String startedAt,
        List<StateCheckDto> checks,
        StateUsageDto executorUsage,
        StateJudgeUsageDto judgeUsage) {}
