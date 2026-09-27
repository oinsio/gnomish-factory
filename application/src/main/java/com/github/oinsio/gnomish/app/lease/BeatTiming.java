package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import java.time.Duration;

/**
 * The <em>beat timing</em> of one instance heartbeat: how often it beats, and how far a claim's
 * last confirmed beat may fall behind before its holder stops writing (design D12 of
 * collapse-composition-roots). The second is a multiple of the first and means nothing without it —
 * which is why the two travel as one value rather than as two adjacent {@link Duration}s a caller
 * could transpose.
 *
 * <p>The invariant the pair owns: the lost-detection threshold is never shorter than the interval,
 * since a holder cannot judge a claim unconfirmed before a single beat has had time to land. {@link
 * LeaseThresholds#beatTiming} is the one production derivation from config, and it keeps the
 * threshold strictly shorter than the reaper's reassignment deadline as well (FR13 of
 * harden-task-branch-contract).
 *
 * <p>Implements FR1 of add-claim-heartbeat; FR13 of harden-task-branch-contract; FR4 of
 * harden-logging-observability; FR1 of collapse-composition-roots.
 *
 * @param interval the beat interval (design D8 of add-claim-heartbeat, default five minutes)
 * @param lostDetection how far a claim's last confirmed beat may fall behind before the holder
 *     self-fences at its next boundary (FR13 of harden-task-branch-contract)
 */
public record BeatTiming(Duration interval, Duration lostDetection) {

    public BeatTiming {
        if (lostDetection.compareTo(interval) < 0) {
            throw new IllegalArgumentException(
                    "lost-detection threshold " + lostDetection + " is shorter than the beat interval " + interval);
        }
    }

    /**
     * The quiet period between roll-ups for a loop that ticks every {@link #interval}. A roll-up
     * period equal to the loop's own tick is no suppression at all — every repeat would qualify —
     * and the beat interval's own default (design D8) is exactly the suppressor's default, so the
     * period is derived from the loop rather than taken from the catalog: at most one reminder per
     * six beats, and never more often than {@link RepeatSuppressor#DEFAULT_ROLL_UP_INTERVAL}.
     */
    // @DoNotMutate: provably equivalent mutant. The two arms of the comparison return equal
    //     durations at the boundary — when six beats are exactly the default, `>` and `>=` both
    //     yield the default's own value — so no covering test can distinguish the boundary
    //     mutation (testing.md, "provably equivalent mutant"). HeartbeatRollUpPeriodSpec covers
    //     the method on both sides of the boundary and on the boundary itself.
    @DoNotMutate
    public Duration rollUp() {
        Duration sixBeats = interval.multipliedBy(6);
        return sixBeats.compareTo(RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL) > 0
                ? sixBeats
                : RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL;
    }
}
