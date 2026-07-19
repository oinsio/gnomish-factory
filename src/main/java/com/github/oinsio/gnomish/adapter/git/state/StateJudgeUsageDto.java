package com.github.oinsio.gnomish.adapter.git.state;

import java.util.List;
import java.util.Map;

/**
 * The {@code state.json} contract's {@code judgeUsage} shape: {@code perVote},
 * one per-model token map per cast vote — mirrors {@code status.json}'s {@code
 * JudgeUsageDto} field-for-field, as a distinct class in this package (design
 * D5). Judge tokens stay per-attempt here; they are not folded into the
 * top-level cumulative {@code totals}.
 *
 * <p>Implements FR3, FR4 of add-git-workflow.
 *
 * @param perVote one per-model token map per cast vote, in vote order; possibly
 *     empty
 */
public record StateJudgeUsageDto(List<Vote> perVote) {

    /**
     * One judge vote's per-model token usage.
     *
     * @param tokensByModel token usage keyed by resolved model id; empty when
     *     this vote's tokens are unreported
     */
    public record Vote(Map<String, StateTokenUsageDto> tokensByModel) {}
}
