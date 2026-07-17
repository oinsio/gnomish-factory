package com.github.oinsio.gnomish.status.json;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The JSON contract's executor-usage shape, used for both an attempt's {@code
 * usage} and the report's cumulative {@code totals}: {@code wallMillis}, optional
 * {@code tokensIn}/{@code tokensOut}, {@code byTool} (spec.md). Token and
 * tool-aggregate fields are optional everywhere in the contract (NFR-C1); wall
 * time is always present.
 *
 * <p>Implements FR11, M3, NFR-C1 of add-manual-run.
 *
 * @param wallMillis total wall time in milliseconds, or {@code null} when unknown
 * @param tokensIn input tokens consumed, or {@code null} when unreported
 * @param tokensOut output tokens produced, or {@code null} when unreported
 * @param byTool per-tool aggregates; possibly empty
 */
public record UsageDto(
        @Nullable Long wallMillis,
        @Nullable Long tokensIn,
        @Nullable Long tokensOut,
        List<ByToolDto> byTool) {}
