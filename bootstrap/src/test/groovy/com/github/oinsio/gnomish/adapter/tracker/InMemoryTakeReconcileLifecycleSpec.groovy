package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.TakeReconcileLifecycleSpecBase
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState

/**
 * The concrete {@code InMemoryTracker} instantiation of {@link TakeReconcileLifecycleSpecBase}
 * (task 6.4 of add-claim-heartbeat, M4): lives inside {@code adapter.tracker} — the one place
 * allowed to name a concrete adapter class alongside the {@code app}-package base spec's port-only
 * seams — mirroring the placement rationale of the sibling {@code
 * InMemoryTakeLifecycleReadyToDeliveredSpec}. {@link #reopenAsReady} reuses the harness's seeding
 * hook (which overwrites an already-seeded ref, including its correspondence) to model a delivered
 * task whose tracker finish never landed.
 *
 * <p>Implements FR10, D10, NFR-C1, M4 of add-claim-heartbeat.
 */
class InMemoryTakeReconcileLifecycleSpec extends TakeReconcileLifecycleSpecBase {

    private InMemoryTracker sharedTracker

    @Override
    List seededReadyTrackerAndFactory(TaskRef ref, String title, String body) {
        sharedTracker = new InMemoryTracker()
        new InMemoryTrackerHarness(sharedTracker).seed(
                ref, new TaskSnapshot(ref.id(), title, body), new TrackerTaskState.Ready(), AbortFacts.none())
        [
            sharedTracker,
            new FixedTrackerAdapterFactory({ sharedTracker })
        ]
    }

    @Override
    List<String> thread(Tracker tracker, TaskRef ref) {
        new InMemoryTrackerHarness(tracker as InMemoryTracker).threadAsStrings(ref)
    }

    @Override
    void reopenAsReady(TaskRef ref, String title, String body) {
        new InMemoryTrackerHarness(sharedTracker).seed(
                ref, new TaskSnapshot(ref.id(), title, body), new TrackerTaskState.Ready(), AbortFacts.none())
    }
}
