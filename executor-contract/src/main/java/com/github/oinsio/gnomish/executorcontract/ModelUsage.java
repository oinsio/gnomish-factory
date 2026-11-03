package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * What a run consumed of one resolved model: the four token counts and an optional cost in USD.
 * Wire shape: {@code {"input", "output", "cacheCreation", "cacheRead", "costUsd"?}}, none negative.
 *
 * <p>Implements NFR-O1 of define-executor-contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelUsage(
        @JsonProperty(required = true) long input,
        @JsonProperty(required = true) long output,
        @JsonProperty(required = true) long cacheCreation,
        @JsonProperty(required = true) long cacheRead,
        @Nullable BigDecimal costUsd) {

    /** Refuses a negative count or cost. */
    public ModelUsage {
        WireChecks.atLeast(input, 0, "input");
        WireChecks.atLeast(output, 0, "output");
        WireChecks.atLeast(cacheCreation, 0, "cacheCreation");
        WireChecks.atLeast(cacheRead, 0, "cacheRead");
        WireChecks.nonNegative(costUsd, "costUsd");
    }
}
