package com.github.oinsio.gnomish.domain.engine;

import java.util.List;
import java.util.Map;

/**
 * The per-vote token usage of a judge check: one per-model token map per cast
 * vote, held in {@code perVote} in the order the votes were cast. A critical
 * stage may take several judge votes, so tokens are accounted per vote rather
 * than summed — the raw grain future budget enforcement needs (NFR-C1). Each
 * vote's own map follows the same map-only shape as {@link
 * ExecutorUsage#tokensByModel()}: empty means that vote's tokens are unreported,
 * never a fabricated zero (design D4).
 *
 * <p>{@code perVote} is defensively copied, unmodifiable, and may be empty: no
 * judge check ran on the stage. Inert value data compared by content.
 *
 * <p>Implements FR9, NFR-C1, D4 of add-agent-executor.
 *
 * @param perVote one per-model token map per cast vote, in vote order;
 *     defensively copied, unmodifiable, possibly empty
 */
public record JudgeUsage(List<Map<String, TokenUsage>> perVote) {

    public JudgeUsage {
        perVote = perVote.stream().map(Map::copyOf).toList();
    }

    /** A {@code JudgeUsage} carrying no votes: no judge ran. */
    public static JudgeUsage none() {
        return new JudgeUsage(List.of());
    }
}
