package com.github.oinsio.gnomish.executorcontract.usagewire;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.oinsio.gnomish.executorcontract.ModelUsage;
import java.math.BigDecimal;
import java.util.Map;

/**
 * The cost part of a {@link UsageSnapshotDto}: {@code {"priced", "unpricedTokens", "complete"}} —
 * the sum of the reported costs in USD, the tokens reported without a cost per role token and model
 * id (the four counts, no cost), and whether nothing was left unpriced or unreported. Wire data only.
 *
 * <p>Implements FR16, FR17, NFR-O3 of define-executor-contract.
 *
 * @param priced the sum of the reported costs in USD
 * @param unpricedTokens the tokens reported without a cost, per role token and model id; possibly empty
 * @param complete whether the folded rounds left nothing unpriced or unreported
 */
public record UsageCostDto(
        @JsonProperty(required = true) BigDecimal priced,
        @JsonProperty(required = true) Map<String, Map<String, ModelUsage>> unpricedTokens,
        @JsonProperty(required = true) boolean complete) {}
