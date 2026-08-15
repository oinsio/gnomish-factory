package com.github.oinsio.gnomish.domain.engine;

import java.time.Duration;

/**
 * A per-tool aggregate for one executor round: the tool {@code name}, how many
 * {@code calls} it received, and the {@code totalDuration} of wall time spent
 * across those calls. This is the per-tool breakdown an executor may report as
 * part of {@link ExecutorUsage} (design D5).
 *
 * <p>The {@code name} is non-blank because an aggregate without a tool identity
 * is meaningless. {@code calls} is at least one — an aggregate exists only
 * because the tool was called, so a count below one is a contradiction.
 * {@code totalDuration} is non-negative; {@link Duration#ZERO} is accepted since
 * calls may complete in no measurable time. Inert value data compared by content.
 *
 * <p>Implements FR13, NFR-C1 of add-stage-engine.
 *
 * @param name the tool name; never blank
 * @param calls the number of calls this aggregate covers; at least one
 * @param totalDuration total wall time across those calls; never negative
 */
public record ToolUsage(String name, int calls, Duration totalDuration) {

    public ToolUsage {
        name = requireNonBlank(name, "name");
        calls = requireAtLeastOne(calls, "calls");
        totalDuration = requireNonNegative(totalDuration, "totalDuration");
    }

    /**
     * Fails fast on a blank {@code name}: a per-tool aggregate must name its tool
     * (FR13). Kept as an explicit static method rather than inline in the compact
     * constructor: PIT's record filter suppresses all mutations inside a record's
     * canonical constructor, which would silently exempt this validation from the
     * 100% mutation gate.
     */
    private static String requireNonBlank(String value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("ToolUsage." + component + " must not be blank");
        }
        return value;
    }

    /**
     * Fails fast on a call count below one: an aggregate implies the tool was
     * called at least once (FR13). Explicit static method for the same PIT
     * mutation-gate reason as {@link #requireNonBlank}.
     */
    private static int requireAtLeastOne(int value, String component) {
        if (value < 1) {
            throw new IllegalArgumentException("ToolUsage." + component + " must be at least 1");
        }
        return value;
    }

    /**
     * Fails fast on a negative {@code totalDuration}: aggregated wall time cannot
     * be negative (FR13). Explicit static method for the same PIT mutation-gate
     * reason as {@link #requireNonBlank}.
     */
    private static Duration requireNonNegative(Duration value, String component) {
        if (value.isNegative()) {
            throw new IllegalArgumentException("ToolUsage." + component + " must not be negative");
        }
        return value;
    }
}
