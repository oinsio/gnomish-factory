package com.github.oinsio.gnomish.domain.engine;

import java.util.List;

/**
 * The per-vote token usage of a judge check: one {@link TokenUsage} per cast vote,
 * held in {@code perVote} in the order the votes were cast. A critical stage may
 * take several judge votes, so tokens are accounted per vote rather than summed —
 * the raw grain future budget enforcement needs (design D5, NFR-C1).
 *
 * <p>{@code perVote} is defensively copied, unmodifiable, and may be empty: no
 * judge check ran on the stage, or the judge adapter reported no token counts.
 * Inert value data compared by content.
 *
 * <p>Implements FR13, NFR-C1 of add-stage-engine.
 *
 * @param perVote one token usage per cast vote, in vote order; defensively
 *     copied, unmodifiable, possibly empty
 */
public record JudgeUsage(List<TokenUsage> perVote) {

    public JudgeUsage {
        perVote = List.copyOf(perVote);
    }

    /** A {@code JudgeUsage} carrying no votes: no judge ran, or tokens unreported. */
    public static JudgeUsage none() {
        return new JudgeUsage(List.of());
    }
}
