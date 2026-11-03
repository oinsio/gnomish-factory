package com.github.oinsio.gnomish.adapter.agent;

import java.math.BigDecimal;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The acceptance rule for the cost the agent CLI reports beside one model's counts: the {@code
 * costUSD} field of a stream-json {@code modelUsage} entry (FR22 of define-executor-contract).
 *
 * <p>The cost is taken only as the CLI reported it, never derived: an absent {@code costUSD} is the
 * CLI pricing nothing and yields no cost, silently; a present one that is not a finite, non-negative
 * number is a degraded value, yields no cost — never a zero — and leaves a DEBUG trace naming the
 * model. The adapter never prices tokens itself (NG10). This rule decides the cost only: the entry's
 * tokens are {@link TokenUsageMapper}'s, and an unusable cost never costs the entry its tokens.
 *
 * <p>Telemetry is best-effort (NFR-R2): this class never throws.
 *
 * <p>Implements FR22, NG10 of define-executor-contract.
 */
final class ReportedCost {

    private static final Logger log = LoggerFactory.getLogger(ReportedCost.class);

    private ReportedCost() {}

    /**
     * The cost one {@code modelUsage} entry reported, or {@code null} when it reported none in a
     * usable shape.
     *
     * @param entry the wire entry; never null
     * @param modelId the entry's model key, already held to {@link ModelIdSyntax}, for the trace
     * @return the reported cost, or {@code null} when absent or unusable
     */
    static @Nullable BigDecimal of(Map<?, ?> entry, String modelId) {
        Object raw = entry.get("costUSD");
        if (raw == null) {
            return null;
        }
        BigDecimal cost = toCost(raw);
        if (cost == null) {
            // throwable-not-subject: the value was classified by its shape, not thrown.
            log.debug(
                    "stream-json: modelUsage entry for model '{}' has an unusable costUSD; it carries no cost",
                    modelId);
        }
        return cost;
    }

    private static @Nullable BigDecimal toCost(Object raw) {
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            return null;
        }
        BigDecimal cost = new BigDecimal(number.toString());
        return cost.signum() < 0 ? null : cost;
    }
}
