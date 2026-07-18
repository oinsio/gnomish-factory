package com.github.oinsio.gnomish.status.json;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The JSON contract's executor-usage shape, used for both an attempt's {@code
 * usage} and the report's cumulative {@code totals}: {@code wallMillis}, {@code
 * tokensByModel} (a map from resolved model id to its four token counts), {@code
 * byTool} (spec.md). Token and tool-aggregate fields are optional everywhere in
 * the contract (NFR-C1): an empty {@code tokensByModel} map means unreported —
 * never a fabricated zero entry; wall time is always present.
 *
 * <p>Implements FR5, FR9, NFR-C1, D12 of add-agent-executor.
 *
 * @param wallMillis total wall time in milliseconds, or {@code null} when unknown
 * @param tokensByModel token usage keyed by resolved model id; possibly empty
 *     when unreported
 * @param byTool per-tool aggregates; possibly empty
 */
public record UsageDto(@Nullable Long wallMillis, Map<String, TokenUsageDto> tokensByModel, List<ByToolDto> byTool) {}
