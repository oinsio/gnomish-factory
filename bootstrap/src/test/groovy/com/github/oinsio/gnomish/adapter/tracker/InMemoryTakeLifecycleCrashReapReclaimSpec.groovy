package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.TakeLifecycleCrashReapReclaimSpecBase
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * The concrete {@code InMemoryTracker} instantiation of {@link TakeLifecycleCrashReapReclaimSpecBase}
 * (FR6, NFR-R1 of fix-claim-epoch-fence): lives inside {@code adapter.tracker} — the one place
 * allowed to name a concrete adapter class alongside the {@code app}-package base spec's port-only
 * seams — the same placement {@link InMemoryTakeLifecycleEscalateResumeSpec} documents.
 */
// The inherited feature reclaims a branch whose tip carries the reaped tenure's older epoch and
// delivers it: the older stamp is never a ground for quarantine (the branch half of the scenario;
// GithubTrackerFactsSpec pins the tracker half).
// Scenario: tracker/tracker-port / An older token never marks an artifact stale
class InMemoryTakeLifecycleCrashReapReclaimSpec extends TakeLifecycleCrashReapReclaimSpecBase {

    private InMemoryTrackerHarness harness

    @Override
    List seededReadyTrackerAndFactory(TaskRef ref, String title, String body) {
        InMemoryTracker inMemoryTracker = new InMemoryTracker()
        harness = new InMemoryTrackerHarness(inMemoryTracker)
        harness.seed(ref, new TaskSnapshot(ref.id(), UntrustedText.tracker(title), UntrustedText.tracker(body)), new TrackerTaskState.Ready(), AbortFacts.none())
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
    void reopenAsReady(TaskRef ref, String title, String body) {
        harness.reopenFinished(ref)
    }
}
