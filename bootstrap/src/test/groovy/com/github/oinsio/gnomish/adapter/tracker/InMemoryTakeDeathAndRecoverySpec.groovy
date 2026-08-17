package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.TakeDeathAndRecoverySpecBase
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState

/**
 * The concrete {@code InMemoryTracker} instantiation of {@link TakeDeathAndRecoverySpecBase} (task
 * 6.6 of add-claim-heartbeat, M2): lives inside {@code adapter.tracker} — the one place allowed to
 * name a concrete adapter class alongside the {@code app}-package base spec's port-only seams —
 * exactly the placement {@link InMemoryTakeHeartbeatLifecycleSpec} documents. A dead holder is
 * modelled with {@link InMemoryTrackerHarness#seedWorkingWithClaim}, whose minted claim version is
 * never advanced again once no beat touches it.
 *
 * <p>Implements FR4, G1, M2 of add-claim-heartbeat.
 */
class InMemoryTakeDeathAndRecoverySpec extends TakeDeathAndRecoverySpecBase {

    private InMemoryTrackerHarness harness

    @Override
    List seededReadyTrackerAndFactory() {
        InMemoryTracker inMemoryTracker = new InMemoryTracker()
        harness = new InMemoryTrackerHarness(inMemoryTracker)
        harness.seed(X, new TaskSnapshot(X.id(), 'Add widgets', 'please add widgets'), new TrackerTaskState.Ready(), AbortFacts.none())
        harness.seed(Y, new TaskSnapshot(Y.id(), 'Add gadgets', 'please add gadgets'), new TrackerTaskState.Ready(), AbortFacts.none())
        [
            inMemoryTracker,
            new FixedTrackerAdapterFactory({ inMemoryTracker })
        ]
    }

    @Override
    void deadenClaim(TaskRef ref, String holder) {
        harness.seedWorkingWithClaim(harness.adapter(), ref, holder)
    }

    @Override
    List<String> thread(Tracker tracker, TaskRef ref) {
        new InMemoryTrackerHarness(tracker as InMemoryTracker).threadAsStrings(ref)
    }
}
