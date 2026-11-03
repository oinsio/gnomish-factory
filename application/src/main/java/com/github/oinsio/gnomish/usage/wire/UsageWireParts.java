package com.github.oinsio.gnomish.usage.wire;

import com.github.oinsio.gnomish.domain.engine.ModelUsage;
import com.github.oinsio.gnomish.domain.engine.TokenUsage;
import com.github.oinsio.gnomish.domain.engine.ToolUsage;
import java.time.Duration;
import java.util.List;

/**
 * The inner parts of the usage wire vocabulary both {@link ParticipantUsageWire} and {@link
 * UsageSnapshotWire} spell: a per-tool aggregate and a per-model entry, mapped between the domain and
 * the contract's {@link com.github.oinsio.gnomish.executorcontract.ToolUsage} and {@link
 * com.github.oinsio.gnomish.executorcontract.ModelUsage}, which are the same wire.
 *
 * <p>Implements FR16 of define-executor-contract.
 */
final class UsageWireParts {

    private UsageWireParts() {}

    static List<com.github.oinsio.gnomish.executorcontract.ToolUsage> toTools(List<ToolUsage> tools) {
        return tools.stream()
                .map(tool -> new com.github.oinsio.gnomish.executorcontract.ToolUsage(
                        tool.name(), tool.calls(), tool.totalDuration().toMillis()))
                .toList();
    }

    /**
     * The domain aggregates of a recorded {@code byTool} list. A recorded aggregate always carries
     * its duration, so one without it is refused, never read as zero.
     */
    static List<ToolUsage> fromTools(List<com.github.oinsio.gnomish.executorcontract.ToolUsage> tools) {
        return tools.stream().map(UsageWireParts::fromTool).toList();
    }

    private static ToolUsage fromTool(com.github.oinsio.gnomish.executorcontract.ToolUsage tool) {
        Long totalMillis = tool.totalMillis();
        if (totalMillis == null) {
            throw new IllegalArgumentException("recorded tool aggregate '" + tool.name() + "' has no totalMillis");
        }
        return new ToolUsage(tool.name(), Math.toIntExact(tool.calls()), Duration.ofMillis(totalMillis));
    }

    static com.github.oinsio.gnomish.executorcontract.ModelUsage toModel(ModelUsage usage) {
        TokenUsage tokens = usage.tokens();
        return new com.github.oinsio.gnomish.executorcontract.ModelUsage(
                tokens.input(), tokens.output(), tokens.cacheCreation(), tokens.cacheRead(), usage.costUsd());
    }

    static ModelUsage fromModel(com.github.oinsio.gnomish.executorcontract.ModelUsage usage) {
        return new ModelUsage(tokensOf(usage), usage.costUsd());
    }

    static TokenUsage tokensOf(com.github.oinsio.gnomish.executorcontract.ModelUsage usage) {
        return new TokenUsage(usage.input(), usage.output(), usage.cacheCreation(), usage.cacheRead());
    }
}
