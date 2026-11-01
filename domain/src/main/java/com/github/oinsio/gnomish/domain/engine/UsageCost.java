package com.github.oinsio.gnomish.domain.engine;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The cost part of a {@link UsageSnapshot} (design D15 of define-executor-contract): a lower bound,
 * never a guess. {@code priced} is the sum of every cost the executors reported; {@code
 * unpricedTokens} names, per role and model, the tokens reported without a cost; {@code complete}
 * is true only when every folded entry reported its usage and priced every model it named — an
 * entry with provenance {@code ABSENT} makes the cost incomplete as an unpriced model does, since
 * its cost is unknown, never zero (NFR-O3). A complete cost names no unpriced tokens.
 *
 * <p>Implements FR17, NFR-O3 of define-executor-contract.
 *
 * @param priced the sum of the reported costs in USD; never negative
 * @param unpricedTokens the tokens reported without a cost, per role and model; possibly empty
 * @param complete whether the folded rounds left nothing unpriced or unreported
 */
public record UsageCost(
        BigDecimal priced, Map<ExecutorRole, Map<String, TokenUsage>> unpricedTokens, boolean complete) {

    /** The cost of no rounds: nothing priced, nothing unknown. */
    public static final UsageCost NONE = new UsageCost(BigDecimal.ZERO, Map.of(), true);

    public UsageCost {
        priced = UsageSnapshotChecks.requireNonNegativePriced(priced);
        unpricedTokens = UsageSnapshotChecks.copyOf(unpricedTokens);
        complete = UsageSnapshotChecks.requireIncompleteWhenUnpriced(complete, unpricedTokens);
    }
}
