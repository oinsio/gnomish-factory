package com.github.oinsio.gnomish.executorcontract.usagewire;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.oinsio.gnomish.executorcontract.ModelUsage;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A task's usage snapshot as the record documents spell it (design D15 of define-executor-contract;
 * status-report v1, "Totals carry the task's executor usage"): {@code {"byRole", "cost", "rounds",
 * "through"?}}, shared by {@code state.json}, the status report and the usage report, so the three
 * cannot word it differently. {@code byRole} maps a role token to a resolved model id to the four
 * counts, which never carry a cost: the cost lives in {@code cost} as a lower bound. {@code through}
 * is omitted when no folded round carried a token. Wire data only: the mapper in {@code
 * :application} builds it from the domain snapshot and back.
 *
 * <p>Unknown-token arm: a role key outside the contract's set is refused by the mapper — a snapshot
 * is never read with a role it cannot name.
 *
 * <p>Implements FR16, FR17, NFR-O3 of define-executor-contract.
 *
 * @param byRole the four counts per role token and resolved model id; possibly empty
 * @param cost the cost as a lower bound
 * @param rounds the number of rounds folded
 * @param through the round token of the last folded round that had one, or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UsageSnapshotDto(
        @JsonProperty(required = true) Map<String, Map<String, ModelUsage>> byRole,
        @JsonProperty(required = true) UsageCostDto cost,
        @JsonProperty(required = true) int rounds,
        @Nullable String through) {}
