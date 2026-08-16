package com.github.oinsio.gnomish.usage.json;

import java.util.List;
import java.util.Map;

/**
 * The {@code gnomish usage --json} mini-contract's {@code judgeUsage} shape: {@code perVote}, one
 * per-model token map per cast vote — mirrors {@code status.json.JudgeUsageDto} field-for-field,
 * as a distinct class in this package (design D5).
 *
 * <p>Implements FR14, NFR-C1 of add-git-workflow.
 *
 * @param perVote one per-model token map per cast vote, in vote order; possibly empty
 */
public record JudgeUsageDto(List<Vote> perVote) {

    /**
     * One judge vote's per-model token usage.
     *
     * @param tokensByModel token usage keyed by resolved model id; empty when this vote's tokens
     *     are unreported
     */
    public record Vote(Map<String, TokenUsageDto> tokensByModel) {}
}
