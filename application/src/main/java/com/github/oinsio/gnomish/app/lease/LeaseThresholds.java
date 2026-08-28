package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.time.Duration;

/**
 * The deadlines of a claim lease, derived from one config in one place so their required order
 * cannot drift (claim-heartbeat "Lost-detection strictly precedes reassignment", FR13 of
 * harden-task-branch-contract):
 *
 * <ul>
 *   <li>{@link #lostDetection} — how long a holder tolerates unconfirmed beats before it stops
 *       writing at its next boundary;
 *   <li>{@link #reassignment} — how long a reaper waits on an unchanging claim version before it
 *       returns the task to circulation;
 *   <li>{@link #windowGrace} — how long a frozen write-sequence window must stand before the
 *       reaper repairs it.
 * </ul>
 *
 * <p>The gap between them is the grace window: a holder whose connectivity returns inside it
 * re-verifies its own still-live claim and resumes, and the task is never reaped. Both are whole
 * multiples of the beat interval, and the config rule's validated minimum multiplier of three keeps
 * the window at least two intervals wide.
 *
 * <p>Implements FR13 of harden-task-branch-contract.
 */
public final class LeaseThresholds {

    private LeaseThresholds() {}

    /**
     * How far a claim's last confirmed beat may fall behind before its holder self-fences: one
     * whole interval earlier than {@link #reassignment}, so the holder always stops writing before
     * any other instance can be handed the task.
     *
     * @param config the resolved tracker config carrying the beat interval and TTL multiplier
     * @return the lost-detection threshold; always strictly shorter than {@link #reassignment}
     */
    public static Duration lostDetection(TrackerConfig config) {
        return config.heartbeatInterval().multipliedBy(config.heartbeatTtlMultiplier() - 1L);
    }

    /**
     * How long a claim version may stand unchanged before a reaper returns the task to {@code
     * Ready} — the TTL the staleness memory measures.
     *
     * @param config the resolved tracker config carrying the beat interval and TTL multiplier
     * @return the reassignment threshold
     */
    public static Duration reassignment(TrackerConfig config) {
        return config.heartbeatInterval().multipliedBy(config.heartbeatTtlMultiplier());
    }

    /**
     * How long a frozen write-sequence window — a claim label with no claim comment yet, a
     * footprint with no live tenure — must stand before the reaper repairs it. One whole beat
     * interval longer than {@link #reassignment}, so a sequence interrupted mid-flight always gets
     * more room than a merely silent holder: rolling back a claim another instance is still
     * completing would cost a race no fence arbitrates, while waiting one interval longer costs
     * only latency on a task nobody is working (FR19, FR12 of harden-task-branch-contract).
     *
     * <p>Derived from the same two config values as the other two deadlines — no new knob, and
     * nothing gnome-writable enters the derivation.
     *
     * <p>Implements FR19, FR12 of harden-task-branch-contract.
     *
     * @param config the resolved tracker config carrying the beat interval and TTL multiplier
     * @return the window grace; always strictly longer than {@link #reassignment}
     */
    public static Duration windowGrace(TrackerConfig config) {
        return config.heartbeatInterval().multipliedBy(config.heartbeatTtlMultiplier() + 1L);
    }
}
