package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * The bounds a request hands the executor as hints; an absent bound is no bound. The factory
 * enforces the three time bounds itself, and the ledger enforces budget after the round. Wire shape:
 * {@code {"turns"?, "tokens"?, "moneyUsd"?, "startupReadMillis"?, "silenceMillis"?,
 * "overallMillis"?}}, every integer at least 1 and the money above 0.
 *
 * <p>Implements NFR-R2, NFR-C1 of define-executor-contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Bounds(
        @Nullable Long turns,
        @Nullable Long tokens,
        @Nullable BigDecimal moneyUsd,
        @Nullable Long startupReadMillis,
        @Nullable Long silenceMillis,
        @Nullable Long overallMillis) {

    /** Refuses a bound outside its range. */
    public Bounds {
        WireChecks.atLeast(turns, 1, "bounds.turns");
        WireChecks.atLeast(tokens, 1, "bounds.tokens");
        WireChecks.atLeast(startupReadMillis, 1, "bounds.startupReadMillis");
        WireChecks.atLeast(silenceMillis, 1, "bounds.silenceMillis");
        WireChecks.atLeast(overallMillis, 1, "bounds.overallMillis");
        if (moneyUsd != null && moneyUsd.signum() <= 0) {
            throw new IllegalArgumentException("bounds.moneyUsd must be above 0, was " + moneyUsd);
        }
    }

    /** No bound at all. */
    public static Bounds none() {
        return new Bounds(null, null, null, null, null, null);
    }
}
