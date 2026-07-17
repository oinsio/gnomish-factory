package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.DoNotMutate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

// PIT M4 documented exception (build.gradle has the full rationale): several private
// helpers below carry @DoNotMutate because PIT's Gregor engine crashes its own minion
// JVM (RUN_ERROR, not a real test gap) mutating some bytecode shapes of a record's
// compiler-generated/component-adjacent private methods on JDK 17+ (hcoles/pitest#1285,
// a JVMTI RedefineClasses restriction on NestHost/NestMembers/Record attributes — not
// fixable via PIT config). Every annotated method is otherwise fully covered by
// ExecutorUsageSpec.

/**
 * The telemetry an executor round reports: total {@code wallTime}, the per-tool
 * aggregates, and the round's per-model token map {@code tokensByModel}. {@code
 * wallTime} and {@code tools} stay optional (design D5 of add-stage-engine): an
 * interactive {@code agent-cli} executor may know only wall time, while an {@code
 * api} round may report tokens but no per-tool breakdown.
 *
 * <p>{@code wallTime} is {@code null} when unknown and non-negative when present.
 * {@code tools} is defensively copied, unmodifiable, and may be empty (no
 * breakdown reported). {@code tokensByModel} is defensively copied, unmodifiable,
 * and map-only: an empty map means unreported, preserving "unknown" distinct from
 * "zero" without a redundant total field alongside it (design D4, NFR-C1); a
 * display total is derived on demand by {@link #totalTokens()}, never stored.
 * Keys are resolved model ids. Inert value data compared by content.
 *
 * <p>Implements FR5, NFR-C1, D4 of add-agent-executor.
 *
 * @param wallTime total round wall time, or {@code null} when unknown; never
 *     negative when present
 * @param tools per-tool aggregates; defensively copied, unmodifiable, possibly
 *     empty
 * @param tokensByModel token usage keyed by resolved model id; defensively
 *     copied, unmodifiable, empty when unreported
 */
public record ExecutorUsage(@Nullable Duration wallTime, List<ToolUsage> tools, Map<String, TokenUsage> tokensByModel) {

    public ExecutorUsage {
        wallTime = requireNonNegativeOrNull(wallTime, "wallTime");
        tools = List.copyOf(tools);
        tokensByModel = Map.copyOf(tokensByModel);
    }

    // PIT M4 documented exception (build.gradle has the full rationale): @DoNotMutate because
    // this method's only content is a `new ExecutorUsage(...)` record-construction call, the
    // same RUN_ERROR-triggering shape (hcoles/pitest#1285) as `plus` below — not a real
    // coverage gap. ExecutorUsageSpec's "none() yields ..." test covers it at the ordinary
    // test level.
    /** An {@code ExecutorUsage} that knows nothing: no wall time, no tools, no tokens. */
    @DoNotMutate
    public static ExecutorUsage none() {
        return new ExecutorUsage(null, List.of(), Map.of());
    }

    /**
     * Returns a new {@code ExecutorUsage} that is the cumulative merge of {@code this} and
     * {@code other}, so a task's running total can fold in each round's executor usage
     * (FR5, NFR-C1, design D4). {@link #none()} is the identity for this operation:
     * {@code x.plus(none())} and {@code none().plus(x)} both equal {@code x} by value.
     *
     * <p>{@code wallTime} is null-aware, preserving the "unknown" vs "zero" distinction:
     * both null yields null; exactly one present yields that one; both present yields their
     * sum. {@code tokensByModel} merges by key union — matching model ids sum their four
     * counts field-wise ({@link TokenUsage#plus(TokenUsage)}), distinct ids carry over
     * unchanged; an empty operand map leaves the other operand's entries untouched, so
     * "unreported" never contaminates a running total with a fabricated zero entry.
     *
     * <p>{@code tools} merge by tool {@code name}: matching names accumulate ({@code calls}
     * summed, {@code totalDuration} summed) while distinct names union. The result order is
     * deterministic — this-usage's tools first in their order, then {@code other}'s
     * not-yet-seen tools in their order (a {@link LinkedHashMap} keyed by name preserves it).
     *
     * @param other the usage to fold into this one; never null
     */
    // PIT M4 documented exception (build.gradle has the full rationale): @DoNotMutate because
    // this method's only content is a `new ExecutorUsage(...)` record-construction call, and
    // that shape triggers the same JVMTI RedefineClasses/record-attribute restriction
    // (hcoles/pitest#1285) as the private helpers above — RUN_ERROR, not a real coverage gap.
    // The helpers it delegates to are separately covered (and separately excluded, for the
    // same reason); ExecutorUsageSpec exercises `plus` itself through many both-null/one-present/
    // both-present/tools-merge/tokensByModel-merge scenarios at the ordinary test level.
    @DoNotMutate
    public ExecutorUsage plus(ExecutorUsage other) {
        return new ExecutorUsage(
                sumWallTime(other.wallTime), mergeTools(other.tools), mergeTokens(other.tokensByModel));
    }

    /**
     * Derives the display total across every reported model — the union field-wise sum, or
     * an all-zero {@link TokenUsage} when {@code tokensByModel} is empty. Derived on demand
     * rather than stored: a stored total would be exact redundancy inviting drift from the
     * map it summarizes (design D4).
     *
     * @return the field-wise sum of every entry in {@code tokensByModel}; never null
     */
    public TokenUsage totalTokens() {
        var total = new TokenUsage(0L, 0L, 0L, 0L);
        for (var usage : tokensByModel.values()) {
            total = total.plus(usage);
        }
        return total;
    }

    @DoNotMutate
    private @Nullable Duration sumWallTime(@Nullable Duration other) {
        if (wallTime == null) {
            return other;
        }
        if (other == null) {
            return wallTime;
        }
        return wallTime.plus(other);
    }

    @DoNotMutate
    private Map<String, TokenUsage> mergeTokens(Map<String, TokenUsage> other) {
        var merged = new LinkedHashMap<>(tokensByModel);
        other.forEach((model, usage) -> merged.merge(model, usage, TokenUsage::plus));
        return merged;
    }

    @DoNotMutate
    private List<ToolUsage> mergeTools(List<ToolUsage> other) {
        var merged = new LinkedHashMap<String, ToolUsage>();
        for (var tool : tools) {
            accumulate(merged, tool);
        }
        for (var tool : other) {
            accumulate(merged, tool);
        }
        return List.copyOf(merged.values());
    }

    @DoNotMutate
    private static void accumulate(Map<String, ToolUsage> merged, ToolUsage tool) {
        var existing = merged.get(tool.name());
        if (existing == null) {
            merged.put(tool.name(), tool);
        } else {
            merged.put(
                    tool.name(),
                    new ToolUsage(
                            tool.name(),
                            existing.calls() + tool.calls(),
                            existing.totalDuration().plus(tool.totalDuration())));
        }
    }

    /**
     * Fails fast on a negative {@code wallTime} when one is present: a round cannot
     * take negative wall time (FR13 of add-stage-engine). A {@code null} means "unknown" and
     * passes through untouched. Kept as an explicit static method rather than inline in
     * the compact constructor: PIT's record filter suppresses all mutations inside
     * a record's canonical constructor, which would silently exempt this validation
     * from the 100% mutation gate.
     */
    @DoNotMutate
    private static @Nullable Duration requireNonNegativeOrNull(@Nullable Duration value, String component) {
        if (value != null && value.isNegative()) {
            throw new IllegalArgumentException("ExecutorUsage." + component + " must not be negative");
        }
        return value;
    }
}
