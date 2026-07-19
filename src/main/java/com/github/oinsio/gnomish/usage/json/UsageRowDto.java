package com.github.oinsio.gnomish.usage.json;

import java.util.List;

/**
 * The {@code gnomish usage --json} mini-contract's per-round shape: one reconstructed round from
 * {@link com.github.oinsio.gnomish.adapter.git.UsageRow}, carried at full granularity — every
 * model's token breakdown, per-tool aggregates, individual checks, and per-vote judge usage, none
 * summed (that summing is text-mode-only, {@code UsageTextRenderer}'s job).
 *
 * <p>Implements FR14, NFR-C1 of add-git-workflow.
 *
 * @param stage the stage name this round's attempt was recorded under
 * @param round the round's sequence number within the stage
 * @param result the lowerCamel result classification ({@code passed}/{@code
 *     qualityFailure}/{@code cannotVerify}/{@code decisionNeeded})
 * @param startedAt ISO-8601 UTC instant the round began
 * @param checks the verify results produced this round; possibly empty
 * @param executorUsage the round's full executor usage
 * @param judgeUsage the round's per-vote judge token usage
 */
public record UsageRowDto(
        String stage,
        int round,
        String result,
        String startedAt,
        List<CheckDto> checks,
        ExecutorUsageDto executorUsage,
        JudgeUsageDto judgeUsage) {}
