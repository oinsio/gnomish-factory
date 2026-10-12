package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.adapter.tracker.inmemory.CorrespondenceEntry
import com.github.oinsio.gnomish.app.TakeHeartbeatLifecycleSpecBase
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker

/**
 * The concrete {@code InMemoryTracker} instantiation of {@link TakeHeartbeatLifecycleSpecBase}
 * (task 6.1 of add-claim-heartbeat, FR1): lives inside {@code adapter.tracker} — the one place
 * allowed to name a concrete adapter class alongside the {@code app}-package base spec's port-only
 * seams — exactly the placement {@link InMemoryTakeLifecycleReadyToDeliveredSpec} documents. A beat
 * shows up on the in-memory tracker as a {@link CorrespondenceEntry.Kind#HEARTBEAT} line on the
 * claim's correspondence thread, so counting those tells the base spec how many times the held
 * claim was beaten.
 *
 * <p>Implements FR1 of add-claim-heartbeat.
 */
class InMemoryTakeHeartbeatLifecycleSpec extends TakeHeartbeatLifecycleSpecBase {

    @Override
    List seededReadyTrackerAndFactory(TaskRef ref, String title, String body) {
        InMemoryHeartbeatSeams.seededReady(ref, title, body)
    }

    @Override
    int heartbeatCount(Tracker tracker, TaskRef ref) {
        InMemoryHeartbeatSeams.count(tracker, ref, CorrespondenceEntry.Kind.HEARTBEAT)
    }
}
