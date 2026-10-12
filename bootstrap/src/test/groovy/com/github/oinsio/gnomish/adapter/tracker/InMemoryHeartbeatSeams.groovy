package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.adapter.tracker.inmemory.CorrespondenceEntry
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * The {@code InMemoryTracker} answers to the seams of {@code TakeHeartbeatFixtureBase} (FR1 of
 * add-claim-heartbeat), shared by the concrete heartbeat specs of this package: a beat, and a
 * reaper's removal, each show up on the claim's correspondence thread as an entry of its own kind.
 */
final class InMemoryHeartbeatSeams {

    private InMemoryHeartbeatSeams() {}

    /** @return {@code [Tracker, TrackerAdapterFactory]} for one fresh Ready task seeded at {@code ref} */
    static List seededReady(TaskRef ref, String title, String body) {
        InMemoryTracker tracker = new InMemoryTracker()
        new InMemoryTrackerHarness(tracker).seed(
                ref, new TaskSnapshot(ref.id(), UntrustedText.tracker(title), UntrustedText.tracker(body)), new TrackerTaskState.Ready(), AbortFacts.none())
        [
            tracker,
            new FixedTrackerAdapterFactory({ tracker })
        ]
    }

    /** @return how many entries of {@code kind} the thread of {@code ref} carries */
    static int count(Tracker tracker, TaskRef ref, CorrespondenceEntry.Kind kind) {
        new InMemoryTrackerHarness(tracker as InMemoryTracker).thread(ref).count {
            it.kind() == kind
        }
    }
}
