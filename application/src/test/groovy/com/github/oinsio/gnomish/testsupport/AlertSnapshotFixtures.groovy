package com.github.oinsio.gnomish.testsupport

import com.github.oinsio.gnomish.serveobservability.FeedPhase
import com.github.oinsio.gnomish.serveobservability.FeedSnapshot
import com.github.oinsio.gnomish.serveobservability.HeartbeatState
import com.github.oinsio.gnomish.serveobservability.HeartbeatVital
import com.github.oinsio.gnomish.serveobservability.LifecycleState
import com.github.oinsio.gnomish.serveobservability.ReaperVital
import com.github.oinsio.gnomish.serveobservability.SlotEntry
import com.github.oinsio.gnomish.serveobservability.SlotsSnapshot
import com.github.oinsio.gnomish.serveobservability.Snapshot
import com.github.oinsio.gnomish.serveobservability.TrackerHealth
import com.github.oinsio.gnomish.serveobservability.VitalsSnapshot
import java.time.Instant

/**
 * Snapshot builders for the alert-condition specs: one healthy baseline and
 * a variant per daemon alert rule, each differing from the baseline in
 * exactly the field its rule reads. Shared by the status-card specs so the
 * rule matrix and the stat-tile assertions exercise the same shapes.
 */
class AlertSnapshotFixtures {

    static final Instant WRITTEN_AT = Instant.parse('2026-08-06T09:00:00Z')
    static final Instant NOW = WRITTEN_AT.plusSeconds(60)

    static Snapshot healthySnapshot() {
        BaselineSnapshotFixture.baselineSnapshot(WRITTEN_AT, new LifecycleState.Running())
    }

    static Snapshot snapshotWithOccupiedSlotsDeadHeartbeat() {
        def base = healthySnapshot()
        def slots = new SlotsSnapshot(3, [
            new SlotEntry('task-1', 'implement', 1, WRITTEN_AT)
        ])
        def vitals = new VitalsSnapshot(
                new HeartbeatVital(HeartbeatState.DIED, WRITTEN_AT, 0), base.vitals().reaper(), base.vitals().janitor())
        return withSlotsAndVitals(base, slots, vitals)
    }

    static Snapshot snapshotWithLongIdleBlocked() {
        def base = healthySnapshot()
        def feed = new FeedSnapshot(FeedPhase.IDLE_BLOCKED, NOW.minusSeconds(31 * 60), WRITTEN_AT, 0, 3)
        return new Snapshot(base.version(), base.writtenAt(), base.intervalSeconds(), base.instance(),
                base.lifecycle(), feed, base.slots(), base.vitals(), base.tracker())
    }

    static Snapshot snapshotWithTrackerFailures() {
        def base = healthySnapshot()
        def tracker = new TrackerHealth(WRITTEN_AT, 3)
        return new Snapshot(base.version(), base.writtenAt(), base.intervalSeconds(), base.instance(),
                base.lifecycle(), base.feed(), base.slots(), base.vitals(), tracker)
    }

    static Snapshot snapshotWithDegradedReaper() {
        def base = healthySnapshot()
        def vitals = new VitalsSnapshot(
                base.vitals().heartbeat(), new ReaperVital(WRITTEN_AT.minusSeconds(1000), 0, 300L), base.vitals().janitor())
        return withSlotsAndVitals(base, base.slots(), vitals)
    }

    static Snapshot withSlotsAndVitals(Snapshot base, SlotsSnapshot slots, VitalsSnapshot vitals) {
        return new Snapshot(base.version(), base.writtenAt(), base.intervalSeconds(), base.instance(),
                base.lifecycle(), base.feed(), slots, vitals, base.tracker())
    }
}
