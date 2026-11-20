package com.github.oinsio.gnomish.adapter.agent.claude;

import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.executorcontract.ModelUsage;
import com.github.oinsio.gnomish.executorcontract.ToolUsage;
import com.github.oinsio.gnomish.executorcontract.Usage;
import com.github.oinsio.gnomish.executorcontract.UsageProvenance;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Claude round's telemetry, derived in the engine's shape by {@link
 * AgentRoundResultExtractor#usage}, written in the contract's (FR3 of extract-agent-round-runner):
 * the per-model tokens and reported cost, the top-level tool aggregate, and the provenance — a
 * round whose stream reported no usable model usage is {@code ABSENT}, never a reported zero. The
 * wall time is left to the runner, which measures it.
 *
 * <p>Implements FR3 of extract-agent-round-runner.
 */
final class ContractUsage {

    private ContractUsage() {}

    /**
     * The contract's usage for {@code usage}.
     *
     * @param usage the round's usage in the engine's shape; never null
     * @return the same usage in the contract's shape, with no wall time; never null
     */
    static Usage of(ExecutorUsage usage) {
        Map<String, ModelUsage> models = new LinkedHashMap<>();
        usage.byModel().forEach((model, entry) -> {
            var tokens = entry.tokens();
            models.put(
                    model,
                    new ModelUsage(
                            tokens.input(),
                            tokens.output(),
                            tokens.cacheCreation(),
                            tokens.cacheRead(),
                            entry.costUsd()));
        });
        var tools = usage.tools().stream()
                .map(tool -> new ToolUsage(
                        tool.name(), tool.calls(), tool.totalDuration().toMillis()))
                .toList();
        var provenance = models.isEmpty() ? UsageProvenance.ABSENT : UsageProvenance.REPORTED;
        return new Usage(provenance, null, models, tools);
    }
}
