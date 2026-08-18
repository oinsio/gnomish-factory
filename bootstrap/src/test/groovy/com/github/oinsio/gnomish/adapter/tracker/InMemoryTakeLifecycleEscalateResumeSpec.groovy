package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.TakeLifecycleEscalateResumeSpecBase
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState

/**
 * The concrete {@code InMemoryTracker} instantiation of {@link TakeLifecycleEscalateResumeSpecBase}
 * (task 6.2 of add-tracker-port, M3, NFR-R3): lives inside {@code adapter.tracker} — the one place
 * allowed to name a concrete adapter class alongside the {@code app}-package base spec's port-only
 * seams ({@code Tracker}, {@code TrackerAdapterFactory}) — the same {@code TrackerPortBoundarySpec}
 * (FR1) placement rationale task 6.1's sibling spec already documents.
 *
 * <p>Implements FR9, FR11, FR12, FR13, M3, NFR-R3 of add-tracker-port.
 */
class InMemoryTakeLifecycleEscalateResumeSpec extends TakeLifecycleEscalateResumeSpecBase {

    private InMemoryTrackerHarness harness

    @Override
    List seededReadyTrackerAndFactory(TaskRef ref, String title, String body) {
        InMemoryTracker inMemoryTracker = new InMemoryTracker()
        harness = new InMemoryTrackerHarness(inMemoryTracker)
        harness.seed(ref, new TaskSnapshot(ref.id(), title, body), new TrackerTaskState.Ready(), AbortFacts.none())
        [
            inMemoryTracker,
            new FixedTrackerAdapterFactory({ inMemoryTracker })
        ]
    }

    @Override
    List<String> thread(Tracker tracker, TaskRef ref) {
        new InMemoryTrackerHarness(tracker as InMemoryTracker).threadAsStrings(ref)
    }

    @Override
    void replyAndReturnToReady(TaskRef ref, String replyText) {
        harness.reply(ref, replyText)
        harness.returnToReady(ref)
    }
}
