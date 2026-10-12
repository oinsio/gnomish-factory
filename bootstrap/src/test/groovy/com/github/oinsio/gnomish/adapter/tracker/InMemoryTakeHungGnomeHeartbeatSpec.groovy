package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.adapter.tracker.inmemory.CorrespondenceEntry
import com.github.oinsio.gnomish.app.TakeHungGnomeHeartbeatSpecBase
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker

/**
 * The concrete {@code InMemoryTracker} instantiation of {@link TakeHungGnomeHeartbeatSpecBase}
 * (FR1 of add-claim-heartbeat), placed in {@code adapter.tracker} for the reason {@link
 * InMemoryTakeHeartbeatLifecycleSpec} documents.
 */
class InMemoryTakeHungGnomeHeartbeatSpec extends TakeHungGnomeHeartbeatSpecBase {

    @Override
    List seededReadyTrackerAndFactory(TaskRef ref, String title, String body) {
        InMemoryHeartbeatSeams.seededReady(ref, title, body)
    }

    @Override
    int heartbeatCount(Tracker tracker, TaskRef ref) {
        InMemoryHeartbeatSeams.count(tracker, ref, CorrespondenceEntry.Kind.HEARTBEAT)
    }

    @Override
    boolean reaped(Tracker tracker, TaskRef ref) {
        InMemoryHeartbeatSeams.count(tracker, ref, CorrespondenceEntry.Kind.STALE_CLAIM_REMOVED) > 0
    }
}
