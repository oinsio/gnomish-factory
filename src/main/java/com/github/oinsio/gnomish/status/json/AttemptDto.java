package com.github.oinsio.gnomish.status.json;

import java.util.List;

/**
 * The JSON contract's per-attempt shape carried under {@code currentStage.attempts}:
 * {@code round}, {@code result}, {@code startedAt}, {@code checks}, {@code usage},
 * {@code judgeUsage} (spec.md).
 *
 * <p>Implements FR11, M3 of add-manual-run.
 *
 * @param round the round's sequence number within the current stage
 * @param result the lowerCamel result classification ({@code passed} / {@code
 *     qualityFailure} / {@code cannotVerify} / {@code decisionNeeded})
 * @param startedAt ISO-8601 UTC instant the round began
 * @param checks the verify results produced this round; possibly empty
 * @param usage the round's aggregate executor usage
 * @param judgeUsage the round's per-vote judge token usage
 */
public record AttemptDto(
        int round, String result, String startedAt, List<CheckDto> checks, UsageDto usage, JudgeUsageDto judgeUsage) {}
