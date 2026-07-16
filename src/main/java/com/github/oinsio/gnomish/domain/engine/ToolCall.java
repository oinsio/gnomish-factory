package com.github.oinsio.gnomish.domain.engine;

import java.time.Duration;
import java.time.Instant;

/**
 * One entry in a {@link ToolTrace}: its chronological {@code seq} position, the
 * {@code tool} name, the {@code start} instant, and the {@code duration} the call
 * took. This is the raw per-call detail the engine keeps outside {@code TaskState}
 * and correlates to its attempt by the trace's {@link AttemptKey} header (design
 * D5).
 *
 * <p>The {@code seq} is non-negative — it is the call's position in the trace; the
 * engine assigns the ordering, so monotonicity is its concern and is not enforced
 * here. The {@code tool} is non-blank because a call without a tool identity is
 * meaningless. The {@code duration} is non-negative; {@link Duration#ZERO} is
 * accepted since a call may complete in no measurable time. Inert value data
 * compared by content.
 *
 * <p>Implements FR13 of add-stage-engine.
 *
 * @param seq the chronological position within the trace; never negative
 * @param tool the tool name; never blank
 * @param start when the call started; never null
 * @param duration how long the call took; never negative
 */
public record ToolCall(int seq, String tool, Instant start, Duration duration) {

    public ToolCall {
        seq = requireNonNegative(seq, "seq");
        tool = requireNonBlank(tool, "tool");
        duration = requireNonNegative(duration, "duration");
    }

    /**
     * Fails fast on a negative {@code seq}: a chronological position cannot be
     * negative (FR13). Kept as an explicit static method rather than inline in the
     * compact constructor: PIT's record filter suppresses all mutations inside a
     * record's canonical constructor, which would silently exempt this validation
     * from the 100% mutation gate.
     */
    private static int requireNonNegative(int value, String component) {
        if (value < 0) {
            throw new IllegalArgumentException("ToolCall." + component + " must not be negative");
        }
        return value;
    }

    /**
     * Fails fast on a blank {@code tool}: a call must name the tool it invoked
     * (FR13). Explicit static method for the same PIT mutation-gate reason as
     * {@link #requireNonNegative(int, String)}.
     */
    private static String requireNonBlank(String value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("ToolCall." + component + " must not be blank");
        }
        return value;
    }

    /**
     * Fails fast on a negative {@code duration}: a call cannot take negative wall
     * time (FR13). Explicit static method for the same PIT mutation-gate reason as
     * {@link #requireNonNegative(int, String)}.
     */
    private static Duration requireNonNegative(Duration value, String component) {
        if (value.isNegative()) {
            throw new IllegalArgumentException("ToolCall." + component + " must not be negative");
        }
        return value;
    }
}
