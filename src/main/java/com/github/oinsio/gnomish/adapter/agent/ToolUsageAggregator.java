package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.domain.engine.ToolCall;
import com.github.oinsio.gnomish.domain.engine.ToolUsage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives a round's per-tool {@link ToolUsage} aggregates from the top-level
 * {@link ToolCall} trace {@link ToolTraceBuilder} builds (design D3): {@code
 * ExecutorUsage.tools} is derived from the trace, never tracked separately, so
 * the trace stays the one source of truth (FR6).
 *
 * <p>Grouping is by tool {@code name}; {@code calls} is the count of trace
 * entries sharing that name, {@code totalDuration} their summed {@code
 * duration}. Result order is deterministic — first-seen order of each tool name
 * across the trace.
 *
 * <p>Implements FR6, D3 of add-agent-executor.
 */
public final class ToolUsageAggregator {

    /**
     * Aggregates {@code trace} into one {@link ToolUsage} per distinct tool name.
     *
     * @param trace the round's top-level tool trace, any order; never null
     * @return per-tool aggregates in first-seen order; never null, empty when
     *     {@code trace} is empty
     */
    public List<ToolUsage> aggregate(List<ToolCall> trace) {
        Map<String, ToolUsage> aggregates = new LinkedHashMap<>();
        for (ToolCall call : trace) {
            aggregates.merge(
                    call.tool(),
                    new ToolUsage(call.tool(), 1, call.duration()),
                    (existing, addition) -> new ToolUsage(
                            call.tool(),
                            existing.calls() + addition.calls(),
                            existing.totalDuration().plus(addition.totalDuration())));
        }
        return List.copyOf(aggregates.values());
    }
}
