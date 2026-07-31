package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.adapter.tracker.inmemory.CorrespondenceEntry
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.TakeHeartbeatLifecycleSpecBase
import com.github.oinsio.gnomish.app.TrackerAdapterFactory
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig

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
        InMemoryTracker tracker = new InMemoryTracker()
        new InMemoryTrackerHarness(tracker).seed(
                ref, new TaskSnapshot(ref.id(), title, body), new TrackerTaskState.Ready(), AbortFacts.none())
        def factory = new TrackerAdapterFactory() {
                    Tracker create(TrackerConfig config, String instanceId) {
                        tracker
                    }

                    TaskRef expandRef(TrackerConfig config, String rawRef) {
                        throw new UnsupportedOperationException('not used by this fixture: ref is already canonical')
                    }
                }
        [tracker, factory]
    }

    @Override
    int heartbeatCount(Tracker tracker, TaskRef ref) {
        def harness = new InMemoryTrackerHarness(tracker as InMemoryTracker)
        harness.thread(ref).count { it.kind() == CorrespondenceEntry.Kind.HEARTBEAT }
    }
}
