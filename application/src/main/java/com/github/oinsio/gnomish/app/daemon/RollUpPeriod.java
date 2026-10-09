package com.github.oinsio.gnomish.app.daemon;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import java.time.Duration;

/**
 * The repeat-suppression period of a loop, derived from the loop's own interval (design D2 of
 * supervise-daemon-loops-and-embed-dashboard). A roll-up period equal to the loop's tick is no
 * suppression at all — every repeat outlives the quiet period and qualifies as a roll-up — and the
 * catalog's {@link RepeatSuppressor#DEFAULT_ROLL_UP_INTERVAL} equals the reaper's and the
 * heartbeat's default interval, so the period is taken from the loop, never from the catalog: at
 * most one reminder per six ticks, and never more often than the catalog default.
 *
 * <p>The one owner of this rule (the single-owner table of the change's design): {@link
 * SupervisedLoop} builds its suppressor with it, and {@code BeatTiming.rollUp()} delegates to it
 * for the heartbeat, which is exempt from the loop but shares the rule. This file is the only
 * reader of the catalog default among the loops (pinned by {@code DaemonLoopOwnerBoundarySpec}).
 *
 * <p>Implements FR2 of supervise-daemon-loops-and-embed-dashboard; FR4 of
 * harden-logging-observability.
 */
public final class RollUpPeriod {

    private static final int TICKS_PER_ROLL_UP = 6;

    private RollUpPeriod() {}

    /**
     * The quiet period between roll-ups for a loop that ticks every {@code interval}.
     *
     * @param interval the loop's wait between ticks; never null
     * @return six intervals, or the catalog default when that is longer; never null
     */
    // @DoNotMutate: provably equivalent mutant. The two arms of the comparison return equal
    //     durations at the boundary — when six intervals are exactly the default, `>` and `>=` both
    //     yield the default's own value — so no covering test can distinguish the boundary
    //     mutation (testing.md, "provably equivalent mutant"). RollUpPeriodSpec covers the method
    //     on both sides of the boundary and on the boundary itself.
    @DoNotMutate
    public static Duration forInterval(Duration interval) {
        Duration sixTicks = interval.multipliedBy(TICKS_PER_ROLL_UP);
        return sixTicks.compareTo(RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL) > 0
                ? sixTicks
                : RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL;
    }
}
