package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.DoNotMutate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

// PIT M4 documented exception (build.gradle has the full rationale): the methods below carry
// @DoNotMutate because PIT's Gregor engine crashes its own minion JVM (RUN_ERROR, not a real
// test gap) mutating some bytecode shapes of a record's methods on JDK 17+ (hcoles/pitest#1285,
// a JVMTI RedefineClasses restriction on NestHost/NestMembers/Record attributes — not fixable
// via PIT config). Every annotated method is otherwise fully covered by ExecutorUsageSpec.

/**
 * The telemetry an executor round reports: total {@code wallTime}, the per-tool
 * aggregates, and the round's per-model usage {@code byModel} — each model's four token counts
 * and the cost the executor reported beside them. {@code wallTime} and {@code tools} stay
 * optional (design D5 of add-stage-engine): an executor may know only wall time, or report
 * tokens but no per-tool breakdown.
 *
 * <p>{@code wallTime} is {@code null} when unknown and non-negative when present.
 * {@code tools} is defensively copied, unmodifiable, and may be empty (no
 * breakdown reported). {@code byModel} is defensively copied, unmodifiable,
 * and map-only: an empty map means unreported, preserving "unknown" distinct from
 * "zero" without a redundant total field alongside it (design D4, NFR-C1). It is one round's
 * report and carries no arithmetic: the task's sums are the {@link UsageFold}'s, over the round's
 * participant entries (design D15 of define-executor-contract). A model's cost
 * is absent when the executor priced nothing for it, never a fabricated zero (FR22 of
 * define-executor-contract). Keys are resolved model ids. Inert value data compared by content.
 *
 * <p>{@code provenance} says whether the executor reported its usage at all (NFR-O1 of
 * define-executor-contract). The agent CLI has no word for it, so its carrier derives it from the
 * map ({@link UsageProvenance#of}); a contract executor says it in its result, and a program that
 * reports usage of no model at all — a deterministic tool — is {@code REPORTED} with an empty map,
 * not {@code ABSENT}. An {@code ABSENT} carrier holds no model.
 *
 * <p>Implements FR5, NFR-C1, D4 of add-agent-executor; FR15, FR22, NFR-O1 of define-executor-contract.
 *
 * @param wallTime total round wall time, or {@code null} when unknown; never
 *     negative when present
 * @param tools per-tool aggregates; defensively copied, unmodifiable, possibly
 *     empty
 * @param byModel usage keyed by resolved model id; defensively copied, unmodifiable, empty when
 *     unreported
 * @param provenance whether the executor reported its usage; never null
 */
public record ExecutorUsage(
        @Nullable Duration wallTime,
        List<ToolUsage> tools,
        Map<String, ModelUsage> byModel,
        UsageProvenance provenance) {

    public ExecutorUsage {
        wallTime = requireNonNegativeOrNull(wallTime, "wallTime");
        tools = List.copyOf(tools);
        byModel = Map.copyOf(byModel);
        UsageProvenance.requireConsistent(provenance, byModel);
    }

    /**
     * A carrier whose provenance is its map's: {@code ABSENT} exactly when nothing was reported —
     * the agent CLI's rule, which has no provenance word of its own.
     *
     * @param wallTime total round wall time, or {@code null} when unknown
     * @param tools per-tool aggregates
     * @param byModel usage keyed by resolved model id
     */
    public ExecutorUsage(@Nullable Duration wallTime, List<ToolUsage> tools, Map<String, ModelUsage> byModel) {
        this(wallTime, tools, byModel, UsageProvenance.of(byModel));
    }

    // PIT M4 documented exception (build.gradle has the full rationale): @DoNotMutate because
    // this method's only content is a `new ExecutorUsage(...)` record-construction call, the
    // same RUN_ERROR-triggering shape (hcoles/pitest#1285) as the check below — not a real
    // coverage gap. ExecutorUsageSpec's "none() yields ..." test covers it at the ordinary
    // test level.
    /** An {@code ExecutorUsage} that knows nothing: no wall time, no tools, no tokens. */
    @DoNotMutate
    public static ExecutorUsage none() {
        return new ExecutorUsage(null, List.of(), Map.of());
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
