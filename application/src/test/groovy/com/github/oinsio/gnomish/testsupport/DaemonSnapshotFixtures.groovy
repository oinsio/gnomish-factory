package com.github.oinsio.gnomish.testsupport

import com.github.oinsio.gnomish.serveobservability.LifecycleState
import com.github.oinsio.gnomish.serveobservability.Snapshot
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
        BaselineSnapshotFixture.baselineSnapshot(WRITTEN_AT, lifecycle)
    }
}
