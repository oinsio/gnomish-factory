package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.TakeLifecycleReadyToDeliveredSpecBase
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * The concrete {@code InMemoryTracker} instantiation of {@link TakeLifecycleReadyToDeliveredSpecBase}
 * (task 6.1 of add-tracker-port, M3, UX4): lives inside {@code adapter.tracker} — the one place
 * allowed to name a concrete adapter class alongside the {@code app}-package base spec's port-only
 * seams ({@code Tracker}, {@code TrackerAdapterFactory}) — exactly the placement rationale {@code
 * TrackerAdapterConfiguration} documents for the same {@code TrackerPortBoundarySpec} (FR1)
 * constraint. Mirrors design D15's "abstract Spock base class extended per adapter" convention,
 * reused here for a CLI-level lifecycle proof rather than the port contract suite itself.
 *
 * <p>Implements FR1, FR3, FR18, M3, UX4 of add-tracker-port.
 */
// The inherited features run a real gnome round as a subprocess, launched with the MCP exclusion,
// and read the tracker's own thread back: claim, progress and finish, every entry written by the
// factory core and none by the gnome (CliStageExecutorCredentialScrubSpec pins that the tracker
// credential never reaches the round).
// Scenario: tracker/tracker-port / Gnome never transitions
class InMemoryTakeLifecycleReadyToDeliveredSpec extends TakeLifecycleReadyToDeliveredSpecBase {

    @Override
    List seededReadyTrackerAndFactory(TaskRef ref, String title, String body) {
        InMemoryTracker tracker = new InMemoryTracker()
        new InMemoryTrackerHarness(tracker).seed(
                ref, new TaskSnapshot(ref.id(), UntrustedText.tracker(title), UntrustedText.tracker(body)), new TrackerTaskState.Ready(), AbortFacts.none())
        [
            tracker,
            new FixedTrackerAdapterFactory({ tracker })
        ]
    }

    @Override
    List<String> thread(Tracker tracker, TaskRef ref) {
        new InMemoryTrackerHarness(tracker as InMemoryTracker).threadAsStrings(ref)
    }
}
