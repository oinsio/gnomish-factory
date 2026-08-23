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
 * Shared healthy-baseline {@link Snapshot} builder used by
 * {@link AlertSnapshotFixtures} and {@link DaemonSnapshotFixtures}: every
 * field is fully populated and nominal except {@code writtenAt} and
 * {@code lifecycle}, which the caller varies.
 */
class BaselineSnapshotFixture {

    static Snapshot baselineSnapshot(Instant writtenAt, LifecycleState lifecycle) {
        new Snapshot(
                1,
                writtenAt,
                30L,
                new InstanceInfo('gnome-1-abcd', 'host1', '1.0.0'),
                lifecycle,
                new FeedSnapshot(FeedPhase.FILLING, writtenAt, writtenAt, 0, 3),
                new SlotsSnapshot(3, []),
                new VitalsSnapshot(
                        new HeartbeatVital(HeartbeatState.RUNNING, writtenAt, 0),
                        new ReaperVital(writtenAt, 0, 300L),
                        new JanitorVital(writtenAt)),
                new TrackerHealth(writtenAt, 0))
    }
}
