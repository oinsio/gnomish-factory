package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.TakeParkReconcileLifecycleSpecBase
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState

/**
 * The concrete {@code InMemoryTracker} instantiation of {@link TakeParkReconcileLifecycleSpecBase}
 * (task 6.5 of add-claim-heartbeat, FR10, D10, NFR-C1): lives inside {@code adapter.tracker} — the
 * one place allowed to name a concrete adapter class alongside the {@code app}-package base spec's
 * port-only seams — mirroring the placement of {@code InMemoryTakeReconcileLifecycleSpec}.
 *
 * <p>Implements FR10, D10, NFR-C1 of add-claim-heartbeat.
 */
class InMemoryTakeParkReconcileLifecycleSpec extends TakeParkReconcileLifecycleSpecBase {

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
