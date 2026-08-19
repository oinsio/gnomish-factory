package com.github.oinsio.gnomish.status.json;

import java.util.List;

/**
 * The JSON contract's per-attempt shape carried under {@code currentStage.attempts}:
 * {@code round}, {@code result}, {@code startedAt}, {@code checks}, {@code denials},
 * {@code usage}, {@code judgeUsage} (spec.md).
 *
 * <p>{@code denials} carries the egress denials of the round, in the same finding
 * shape a failed check's {@code findings} use — additive under contract v1 and
 * always present, empty when the round denied nothing (UX2 of
 * fix-denial-report-attachment). It is observability only and derives nothing:
 * {@code result} reads the same with or without it.
 *
 * <p>Implements FR11, M3 of add-manual-run; FR4 of fix-denial-report-attachment.
 *
 * @param round the round's sequence number within the current stage
 * @param result the lowerCamel result classification ({@code passed} / {@code
 *     qualityFailure} / {@code cannotVerify} / {@code decisionNeeded})
 * @param startedAt ISO-8601 UTC instant the round began
 * @param checks the verify results produced this round; possibly empty
 * @param denials the egress denials recorded during this round; possibly empty
 * @param usage the round's aggregate executor usage
 * @param judgeUsage the round's per-vote judge token usage
 */
public record AttemptDto(
        int round,
        String result,
        String startedAt,
        List<CheckDto> checks,
        List<FindingDto> denials,
        UsageDto usage,
        JudgeUsageDto judgeUsage) {}
