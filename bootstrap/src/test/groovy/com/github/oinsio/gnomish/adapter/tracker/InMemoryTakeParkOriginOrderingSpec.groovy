package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.TakeParkOriginOrderingSpecBase
import com.github.oinsio.gnomish.app.TrackerAdapterFactory
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState

/**
 * The concrete {@code InMemoryTracker} instantiation of {@link TakeParkOriginOrderingSpecBase} (M2,
 * FR4 of fix-lifecycle-push): lives inside {@code adapter.tracker} — the one place allowed to name a
 * concrete adapter class alongside the {@code app}-package base spec's port-only seams.
 */
class InMemoryTakeParkOriginOrderingSpec extends TakeParkOriginOrderingSpecBase {

    @Override
    Tracker seededReadyTracker(TaskRef ref, String title, String body) {
        def tracker = new InMemoryTracker()
        new InMemoryTrackerHarness(tracker).seed(
                ref, new TaskSnapshot(ref.id(), title, body), new TrackerTaskState.Ready(), AbortFacts.none())
        tracker
    }

    @Override
    TrackerAdapterFactory factoryFor(Tracker tracker) {
        new FixedTrackerAdapterFactory({ tracker })
    }
}
