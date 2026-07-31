package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask

/**
 * Test-only {@link Tracker} decorator base shared by the 6.3/6.4 revocation and abort specs
 * ({@link CloseOnNthFetchTracker}, {@link ThrowOnNextFetchTracker}): delegates every operation to
 * {@code delegate} unchanged. Subclasses override only the specific operations (typically {@link
 * #fetchTask} and/or {@link #recordProgress}) needed to simulate the failure scenario under test.
 *
 * <p>Kept in {@code adapter.tracker} (not {@code adapter.tracker.inmemory}) because it is
 * test-support scaffolding, not a reusable in-memory-adapter capability; it may still depend on
 * {@link InMemoryTracker} since this package is the one place {@code TrackerPortBoundarySpec}
 * allows that.
 */
abstract class DelegatingTracker implements Tracker {

    protected final InMemoryTracker delegate

    protected DelegatingTracker(InMemoryTracker delegate) {
        this.delegate = delegate
    }

    @Override
    TrackerTask fetchTask(TaskRef ref) {
        delegate.fetchTask(ref)
    }

    @Override
    List<ReadyTask> listReady(int limit) {
        delegate.listReady(limit)
    }

    @Override
    List<HumanReply> collectDecisions(TaskRef ref) {
        delegate.collectDecisions(ref)
    }

    @Override
    ClaimResult claim(TaskRef ref, String instanceId) {
        delegate.claim(ref, instanceId)
    }

    @Override
    void release(TaskRef ref) {
        delegate.release(ref)
    }

    @Override
    void park(TaskRef ref, ParkReason reason, String report) {
        delegate.park(ref, reason, report)
    }

    @Override
    void finish(TaskRef ref, String summary) {
        delegate.finish(ref, summary)
    }

    @Override
    void recordAbort(TaskRef ref, AbortRecord record) {
        delegate.recordAbort(ref, record)
    }

    @Override
    void acknowledgeDecision(TaskRef ref, String decisionText) {
        delegate.acknowledgeDecision(ref, decisionText)
    }

    @Override
    void postNote(TaskRef ref, String text) {
        delegate.postNote(ref, text)
    }

    @Override
    void recordProgress(TaskRef ref) {
        delegate.recordProgress(ref)
    }

    @Override
    List<OpenTask> listOpen() {
        delegate.listOpen()
    }

    @Override
    HeartbeatResult heartbeat(TaskRef ref, String progressPayload) {
        delegate.heartbeat(ref, progressPayload)
    }

    @Override
    RemoveStaleClaimResult removeStaleClaim(TaskRef ref, ClaimVersion observedVersion) {
        delegate.removeStaleClaim(ref, observedVersion)
    }
}
