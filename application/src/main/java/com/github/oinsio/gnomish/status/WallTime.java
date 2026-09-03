package com.github.oinsio.gnomish.status;

import java.time.Duration;

/**
 * How long a run took, measured the one way every summary assembler must agree on.
 *
 * <p>All three producers of a {@link TaskSummary} — the serve slot ({@code TakeSlotRunner}), the
 * manual-run dispatcher ({@code TakeDispatcher}) and the engine-event accumulator ({@link
 * SummaryAccumulatorListener}) — need this answer, and the rule they have to share is not
 * "subtract two longs" but <em>which clock</em>: a wall clock stepped by NTP mid-run would let a
 * task report a negative duration, which {@link TaskSummary}'s own constructor then rejects — a
 * crash on the summary line rather than a wrong number, but a crash all the same.
 *
 * <p>A class of its own rather than a static on {@link TaskSummary}: a record's class attributes
 * cannot be redefined in place, so the mutation gate cannot exercise a method hosted there
 * ({@code .claude/rules/testing.md}, the JVMTI redefinition limit). The rule this holds is worth
 * a gate.
 *
 * <p>Stateless: a pure function with no fields.
 *
 * <p>Implements FR3 of harden-logging-observability.
 */
public final class WallTime {

    private WallTime() {}

    /**
     * The elapsed duration since {@code startedNanos}, from the same monotonic source that
     * produced it.
     *
     * @param startedNanos a {@link System#nanoTime()} reading taken when the work started
     * @return the elapsed duration; never null, never negative
     */
    public static Duration since(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }
}
