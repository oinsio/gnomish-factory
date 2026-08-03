package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.time.Instant

/**
 * Shared claim-version and open-task builders for the standing-reaper specs ({@link
 * DeadHeartbeatReapingSpec}, {@link ReapingWhileIdleSpec}, {@link ReapingWhileSaturatedSpec}),
 * which previously each defined byte-for-byte identical {@code version}/{@code workingBy}
 * helpers to drive the same REAL {@link InstanceHeartbeat}/{@link StandingReaper} wiring.
 */
class ClaimFixtures {

    private ClaimFixtures() {
    }

    static ClaimVersion version(String marker) {
        new ClaimVersion(marker, Instant.EPOCH)
    }

    static OpenTask workingBy(TaskRef ref, String instance, ClaimVersion v) {
        new OpenTask(ref, new TrackerTaskState.Working(instance), v)
    }
}
