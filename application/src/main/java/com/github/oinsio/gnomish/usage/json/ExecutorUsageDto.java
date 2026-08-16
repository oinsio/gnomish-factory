package com.github.oinsio.gnomish.usage.json;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The {@code gnomish usage --json} mini-contract's executor-usage shape, used for both a row's
 * {@code executorUsage} and the report's cumulative {@code totals}: {@code wallMillis}, {@code
 * tokensByModel} (full per-model granularity, never summed), {@code byTool} — mirrors {@code
 * status.json.UsageDto} field-for-field, as a distinct class in this package (design D5).
 *
 * <p>Implements FR14, NFR-C1 of add-git-workflow.
 *
 * @param wallMillis total wall time in milliseconds, or {@code null} when unknown
 * @param tokensByModel token usage keyed by resolved model id; possibly empty when unreported
 * @param byTool per-tool aggregates; possibly empty
 */
public record ExecutorUsageDto(
        @Nullable Long wallMillis, Map<String, TokenUsageDto> tokensByModel, List<ByToolDto> byTool) {}
