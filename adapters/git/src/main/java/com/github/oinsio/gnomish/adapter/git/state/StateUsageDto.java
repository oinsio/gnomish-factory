package com.github.oinsio.gnomish.adapter.git.state;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The {@code state.json} contract's executor-usage shape, used for both an
 * attempt's {@code executorUsage} and the top-level cumulative {@code totals}:
 * {@code wallMillis}, {@code tokensByModel} (a map from resolved model id to its
 * four token counts), {@code byTool} — mirrors {@code status.json}'s {@code
 * UsageDto} field-for-field, as a distinct class in this package (design D5).
 * Token and tool-aggregate fields are optional everywhere in the contract
 * (NFR-C1): an empty {@code tokensByModel} map means unreported — never a
 * fabricated zero entry; wall time is always present.
 *
 * <p>Implements FR3, FR4 of add-git-workflow.
 *
 * @param wallMillis total wall time in milliseconds, or {@code null} when
 *     unknown
 * @param tokensByModel token usage keyed by resolved model id; possibly empty
 *     when unreported
 * @param byTool per-tool aggregates; possibly empty
 */
public record StateUsageDto(
        @Nullable Long wallMillis, Map<String, StateTokenUsageDto> tokensByModel, List<StateByToolDto> byTool) {}
