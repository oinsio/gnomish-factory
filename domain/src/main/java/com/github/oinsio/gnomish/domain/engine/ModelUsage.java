package com.github.oinsio.gnomish.domain.engine;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * One resolved model's usage within one {@link ParticipantUsage} entry (design D14 of
 * define-executor-contract): the four token counts and the cost in USD the executor reported beside
 * them. The cost is optional — {@code null} when the executor priced nothing, never a fabricated
 * zero — and non-negative when present. Inert value data compared by content.
 *
 * <p>The check lives in {@link ParticipantUsageChecks} and the arithmetic in {@link ModelUsages},
 * outside the record, for the mutation gate (hcoles/pitest#1285).
 *
 * <p>Implements FR15, FR22 of define-executor-contract.
 *
 * @param tokens the four token counts; never null
 * @param costUsd the cost in USD the executor reported, or {@code null} when it reported none
 */
public record ModelUsage(TokenUsage tokens, @Nullable BigDecimal costUsd) {

    public ModelUsage {
        costUsd = ParticipantUsageChecks.requireNonNegativeCost(costUsd);
    }
}
