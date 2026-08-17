package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.TakeLifecycleProgressResetSpecBase
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState

/**
 * The concrete {@code InMemoryTracker} instantiation of {@link TakeLifecycleProgressResetSpecBase}
 * (fix-abort-progress-reset FR3, NFR-C1, M1): lives inside {@code adapter.tracker} — the one place
 * allowed to name a concrete adapter class alongside the {@code app}-package base spec's port-only
 * seams ({@code Tracker}, {@code TrackerAdapterFactory}) — the same {@code TrackerPortBoundarySpec}
 * (FR1 of add-tracker-port) placement rationale the sibling lifecycle specs already document.
 *
 * <p>Implements FR3, NFR-C1, M1 of fix-abort-progress-reset.
 */
class InMemoryTakeLifecycleProgressResetSpec extends TakeLifecycleProgressResetSpecBase {

    private InMemoryTracker realTracker
    private InMemoryTrackerHarness harness

    @Override
    List seededReadyTrackerAndFactory(TaskRef ref, String title, String body) {
        realTracker = new InMemoryTracker()
        harness = new InMemoryTrackerHarness(realTracker)
        harness.seed(ref, new TaskSnapshot(ref.id(), title, body), new TrackerTaskState.Ready(), AbortFacts.none())
        [
            realTracker,
            new FixedTrackerAdapterFactory({ realTracker })
        ]
    }

    @Override
    List<String> thread(Tracker trackerArg, TaskRef ref) {
        harness.threadAsStrings(ref)
    }
}
