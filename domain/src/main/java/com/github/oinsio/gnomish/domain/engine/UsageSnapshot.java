package com.github.oinsio.gnomish.domain.engine;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The task's recorded {@code totals} (glossary "Usage snapshot"; design D15 of
 * define-executor-contract): the {@link UsageFold}'s result over every recorded round — the four
 * token counts per executor role and resolved model id, the {@link UsageCost} as a lower bound, and
 * the snapshot's position: {@code rounds} folded and {@code through}, the round token of the last
 * folded round that had one. Built only by the fold and by the record readers that rebuild a
 * recorded snapshot; never a sum a writer computed on its own.
 *
 * <p>{@code byRole} is deeply copied and unmodifiable; a role is present only with at least one
 * model, so a round that reported nothing adds no zero-valued entry. {@code through} is {@code
 * null} before the first branch-mode round, and stays so in the in-place run, whose rounds carry no
 * token. Inert value data compared by content. The checks live in {@link UsageSnapshotChecks},
 * outside the record, for the mutation gate (hcoles/pitest#1285).
 *
 * <p>Implements FR17, NFR-O3 of define-executor-contract.
 *
 * @param byRole the four token counts per role and resolved model id; possibly empty
 * @param cost the cost of the folded rounds as a lower bound; never null
 * @param rounds the number of rounds folded; never negative
 * @param through the round token of the last folded round that had one, or {@code null}
 */
public record UsageSnapshot(
        Map<ExecutorRole, Map<String, TokenUsage>> byRole,
        UsageCost cost,
        int rounds,
        @Nullable RoundToken through) {

    /** The snapshot of no rounds: no tokens, nothing priced, complete, nothing folded. */
    public static final UsageSnapshot EMPTY = new UsageSnapshot(Map.of(), UsageCost.NONE, 0, null);

    public UsageSnapshot {
        byRole = UsageSnapshotChecks.copyOf(byRole);
        rounds = UsageSnapshotChecks.requireNonNegativeRounds(rounds);
    }
}
