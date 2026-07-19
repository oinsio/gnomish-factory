package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.adapter.git.state.StateTokenUsageDto;
import com.github.oinsio.gnomish.adapter.git.state.StateUsageDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The cumulative totals across every {@link UsageRow} of a {@code gnomish usage} reconstruction
 * (FR14, NFR-C1 of add-git-workflow): every recorded round of every stage visit — including
 * failed attempts — contributes, matching {@code state.json}'s own {@code totals} semantics
 * (design D5) but derived here directly from the walked rows so it stays correct even though the
 * per-stage {@code attempts} list resets on advancement (see {@link UsageHistoryWalker}).
 *
 * <p>Implements FR14, NFR-C1 of add-git-workflow.
 *
 * @param wallMillis summed wall time across every row, or {@code null} if no row reported one
 * @param tokensByModel token usage summed per model id across every row's {@code executorUsage}
 */
public record UsageTotals(@Nullable Long wallMillis, Map<String, StateTokenUsageDto> tokensByModel) {

    /**
     * The zero totals: no rows folded in yet.
     *
     * <p>{@code @DoNotMutate}: PIT's Gregor engine crashes its minion JVM (RUN_ERROR, not a real
     * coverage gap) on some mutations of this record's own methods via JVMTI RedefineClasses — the
     * same documented, still-open PIT limitation on JDK 17+ record classes as {@code
     * ExecutorUsage}/{@code StatusReport}/{@code Finding}/{@code Decision} (see build.gradle's
     * {@code pitest} block and {@link DoNotMutate}'s javadoc). This method is fully covered by
     * {@code UsageTextRendererSpec}'s "no rounds recorded" scenario.
     */
    @DoNotMutate
    public static UsageTotals empty() {
        return new UsageTotals(null, Map.of());
    }

    /**
     * Folds {@code rows} into a single {@link UsageTotals}, in the order given.
     *
     * <p>{@code @DoNotMutate}: see {@link #empty()}'s javadoc for why. Covered by {@code
     * UsageHistoryWalkerSpec}'s multi-round totals scenario and {@code UsageTextRendererSpec}.
     *
     * @param rows the rows to sum; possibly empty
     * @return the cumulative totals; {@link #empty()} if {@code rows} is empty
     */
    @DoNotMutate
    public static UsageTotals of(List<UsageRow> rows) {
        UsageTotals totals = empty();
        for (UsageRow row : rows) {
            totals = totals.plus(row.executorUsage());
        }
        return totals;
    }

    /**
     * {@code @DoNotMutate}: see {@link #empty()}'s javadoc for why. Covered by the same specs as
     * {@link #of(List)}.
     */
    @DoNotMutate
    private UsageTotals plus(StateUsageDto usage) {
        Long mergedWall = mergeWall(wallMillis, usage.wallMillis());
        Map<String, StateTokenUsageDto> merged = new LinkedHashMap<>(tokensByModel);
        usage.tokensByModel().forEach((model, tokens) -> merged.merge(model, tokens, UsageTotals::mergeTokens));
        return new UsageTotals(mergedWall, merged);
    }

    /**
     * {@code @DoNotMutate}: see {@link #empty()}'s javadoc for why. Covered by {@code
     * UsageTextRendererSpec}'s wall-time scenarios (both-present, left-only, and right-only null).
     */
    @DoNotMutate
    private static @Nullable Long mergeWall(@Nullable Long left, @Nullable Long right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left + right;
    }

    /**
     * {@code @DoNotMutate}: see {@link #empty()}'s javadoc for why. Covered by {@code
     * UsageTextRendererSpec}'s multi-model summing scenario.
     */
    @DoNotMutate
    private static StateTokenUsageDto mergeTokens(StateTokenUsageDto left, StateTokenUsageDto right) {
        return new StateTokenUsageDto(
                left.input() + right.input(),
                left.output() + right.output(),
                left.cacheCreation() + right.cacheCreation(),
                left.cacheRead() + right.cacheRead());
    }
}
