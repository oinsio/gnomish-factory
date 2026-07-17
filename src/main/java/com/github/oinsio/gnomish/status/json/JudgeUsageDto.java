package com.github.oinsio.gnomish.status.json;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The JSON contract's {@code judgeUsage} shape: {@code perVote} token counts, one
 * entry per cast vote (spec.md). Judge tokens stay per-attempt here; they are not
 * folded into the report's cumulative {@code totals}.
 *
 * <p>Implements FR11, M3, NFR-C1 of add-manual-run.
 *
 * @param perVote one token pair per cast vote, in vote order; possibly empty
 */
public record JudgeUsageDto(List<Vote> perVote) {

    /**
     * One judge vote's token counts.
     *
     * @param tokensIn input tokens consumed by this vote, or {@code null} when
     *     unreported
     * @param tokensOut output tokens produced by this vote, or {@code null} when
     *     unreported
     */
    public record Vote(@Nullable Long tokensIn, @Nullable Long tokensOut) {}
}
