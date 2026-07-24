package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.TakeLifecycleAbortSpecBase
import com.github.oinsio.gnomish.app.TrackerAdapterFactory
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig

/**
 * The concrete {@code InMemoryTracker} instantiation of {@link TakeLifecycleAbortSpecBase} (task
 * 6.4 of add-tracker-port, FR10, FR14): lives inside {@code adapter.tracker} — the one place
 * allowed to name a concrete adapter class alongside the {@code app}-package base spec's port-only
 * seams ({@code Tracker}, {@code TrackerAdapterFactory}) — the same {@code TrackerPortBoundarySpec}
 * (FR1) placement rationale the 6.1/6.2/6.3 sibling specs already document.
 *
 * <p>{@link #armToAbortOnNextRoundBoundaryCheck} arms {@link ThrowOnNextFetchTracker}, the
 * small test-only {@link Tracker} decorator this task adds (also placed here, the only package
 * allowed to depend on {@link InMemoryTracker}/{@link InMemoryTrackerHarness}), to make the SECOND
 * {@code fetchTask} call of the next run — the round-boundary check, not the pre-dispatch fetch —
 * throw a plain {@code RuntimeException} — an infrastructure failure, not a revocation — instead
 * of answering, then behave normally forever after.
 *
 * <p>Implements FR9, FR10, FR14 of add-tracker-port.
 */
class InMemoryTakeLifecycleAbortSpec extends TakeLifecycleAbortSpecBase {

    private InMemoryTracker realTracker
    private InMemoryTrackerHarness harness
    private ThrowOnNextFetchTracker armedTracker

    @Override
    List seededReadyTrackerAndFactory(TaskRef ref, String title, String body) {
        realTracker = new InMemoryTracker()
        harness = new InMemoryTrackerHarness(realTracker)
        harness.seed(ref, new TaskSnapshot(ref.id(), title, body), new TrackerTaskState.Ready(), AbortFacts.none())
        armedTracker = new ThrowOnNextFetchTracker(realTracker)
        def factory = new TrackerAdapterFactory() {
                    Tracker create(TrackerConfig config, String instanceId) {
                        armedTracker
                    }

                    TaskRef expandRef(TrackerConfig config, String rawRef) {
                        throw new UnsupportedOperationException('not used by this fixture: ref is already canonical')
                    }
                }
        [armedTracker, factory]
    }

    @Override
    List<String> thread(Tracker trackerArg, TaskRef ref) {
        harness.thread(ref).collect { "${it.kind()}: ${it.text()}".toString() }
    }

    @Override
    void armToAbortOnNextRoundBoundaryCheck() {
        armedTracker.armNextRunToAbort()
    }
}

/**
 * Test-only {@link Tracker} decorator (task 6.4, FR14): delegates every operation to {@code
 * delegate} unchanged, except that once {@link #armNextRunToAbort} is called, the SECOND {@code
 * fetchTask} call of the next {@code take} run throws a plain {@code RuntimeException} instead of
 * delegating — standing in for a tracker gone infrastructurally unreachable mid-round, at exactly
 * the round-boundary "still ours and alive" check {@link
 * com.github.oinsio.gnomish.app.take.RevocationCheckingAttemptPersistence#persist} performs after
 * the round's git commit already durably landed. The FIRST {@code fetchTask} call of a {@code take
 * <ref>} run ({@link com.github.oinsio.gnomish.app.TakeCommand#runExplicit}'s own pre-dispatch
 * fetch) is deliberately left untouched — only the round-boundary check is the infrastructure
 * failure this decorator simulates. Re-arming before each {@code take} invocation (as {@link
 * InMemoryTakeLifecycleAbortSpec} does) makes exactly one round boundary per run abort, regardless
 * of how many {@code fetchTask} calls surround it in that or any other run.
 *
 * <p>Kept in {@code adapter.tracker} (not {@code adapter.tracker.inmemory}) because it is
 * test-support scaffolding for this one spec, not a reusable in-memory-adapter capability; it may
 * still depend on {@link InMemoryTracker} since this package is the one place {@code
 * TrackerPortBoundarySpec} allows that.
 */
class ThrowOnNextFetchTracker implements Tracker {

    private final InMemoryTracker delegate
    private boolean armed = false
    private int fetchCountSinceArmed = 0

    ThrowOnNextFetchTracker(InMemoryTracker delegate) {
        this.delegate = delegate
    }

    /** Arms the SECOND {@link #fetchTask} call of the next run to throw instead of delegating. */
    void armNextRunToAbort() {
        armed = true
        fetchCountSinceArmed = 0
    }

    @Override
    TrackerTask fetchTask(TaskRef ref) {
        if (armed) {
            fetchCountSinceArmed++
            if (fetchCountSinceArmed == 2) {
                armed = false
                throw new RuntimeException('simulated tracker infrastructure failure at round boundary')
            }
        }
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
}
