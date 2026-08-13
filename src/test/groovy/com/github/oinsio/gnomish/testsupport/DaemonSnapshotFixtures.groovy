package com.github.oinsio.gnomish.testsupport

import com.github.oinsio.gnomish.serveobservability.FeedPhase
import com.github.oinsio.gnomish.serveobservability.FeedSnapshot
import com.github.oinsio.gnomish.serveobservability.HeartbeatState
import com.github.oinsio.gnomish.serveobservability.HeartbeatVital
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.JanitorVital
import com.github.oinsio.gnomish.serveobservability.LifecycleState
import com.github.oinsio.gnomish.serveobservability.ReaperVital
import com.github.oinsio.gnomish.serveobservability.SlotsSnapshot
import com.github.oinsio.gnomish.serveobservability.Snapshot
import com.github.oinsio.gnomish.serveobservability.TrackerHealth
import com.github.oinsio.gnomish.serveobservability.VitalsSnapshot
import java.time.Instant

/**
 * A daemon {@link Snapshot} fixture shared by {@code DashboardHtmlRenderer*Spec}
 * tests: builds a healthy, fully-populated snapshot for the given lifecycle
 * state, timestamped at {@link #WRITTEN_AT}, so each spec only needs to vary
 * the lifecycle it is actually exercising.
 */
class DaemonSnapshotFixtures {

    static final Instant WRITTEN_AT = Instant.parse('2026-08-06T08:59:00Z')

    static Snapshot snapshot(LifecycleState lifecycle) {
        new Snapshot(
                1,
                WRITTEN_AT,
                30L,
                new InstanceInfo('gnome-1-abcd', 'host1', '1.0.0'),
                lifecycle,
                new FeedSnapshot(FeedPhase.FILLING, WRITTEN_AT, WRITTEN_AT, 0, 3),
                new SlotsSnapshot(3, []),
                new VitalsSnapshot(
                        new HeartbeatVital(HeartbeatState.RUNNING, WRITTEN_AT, 0),
                        new ReaperVital(WRITTEN_AT, 0, 300L),
                        new JanitorVital(WRITTEN_AT)),
                new TrackerHealth(WRITTEN_AT, 0))
    }
}
