package com.github.oinsio.gnomish.status.json;

import java.util.List;
import java.util.Map;

/**
 * The JSON contract's {@code judgeUsage} shape: {@code perVote}, one per-model
 * token map per cast vote (spec.md). Judge tokens stay per-attempt here; they are
 * not folded into the report's cumulative {@code totals}.
 *
 * <p>Implements FR9, D12 of add-agent-executor.
 *
 * @param perVote one per-model token map per cast vote, in vote order; possibly
 *     empty
 */
public record JudgeUsageDto(List<Vote> perVote) {

    /**
     * One judge vote's per-model token usage.
     *
     * @param tokensByModel token usage keyed by resolved model id; empty when
     *     this vote's tokens are unreported
     */
    public record Vote(Map<String, TokenUsageDto> tokensByModel) {}
}
