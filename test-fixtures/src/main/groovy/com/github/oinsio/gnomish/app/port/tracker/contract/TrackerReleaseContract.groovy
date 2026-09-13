package com.github.oinsio.gnomish.app.port.tracker.contract

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState

/**
 * Plain-release properties of the {@link Tracker} port contract suite (tracker-port spec,
 * {@code release}: "drops the caller's claim without otherwise changing the task's logical
 * state"; FR15, D2 of add-tracker-port). Extends {@link TrackerDesignatorContract} to reuse the
 * {@code arrange}/{@code seedWorkingWithClaim} seams; a concrete adapter subclass instantiates
 * THIS class to run the full suite, per M1 of add-tracker-port.
 *
 * <p>What these properties pin is the half of {@code release} that every caller outside the
 * revocation path had started to assume the other way round: a released task is <em>not</em>
 * back in the ready queue. The working label stays; what returns the task to {@code Ready} is
 * the reaper of the {@code claim-heartbeat} capability, once the claim has gone stale. The two
 * shipped adapters realize "state untouched" differently — the in-memory reference drops the
 * claim marker, the GitHub adapter does nothing at all — and the properties hold for both,
 * which is the point: a caller may rely on neither a cleared footprint nor an instant return,
 * only on the reaper. The deliberate, fenced immediate return is a separate verb
 * ({@code add-claim-return}), not a meaning this one quietly grows.
 *
 * <p>Implements FR15 of add-tracker-port; FR9, NFR-R3 of add-base-ref-resolution.
 */
abstract class TrackerReleaseContract extends TrackerDesignatorContract {

    // FR15 of add-tracker-port: release drops the claim and nothing else — the task stays
    //     Working and is NOT listed among the ready tasks a fresh claim draws from
    def "release leaves the task Working and out of the ready queue"() {
        given: 'a task this instance claimed through the port'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'release fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:release-plain')
        claimedByA(adapter, ref)

        when: 'the holder releases its claim'
        adapter.release(ref)

        then: 'the logical state is untouched — still Working — and the task is not ready'
        adapter.fetchTask(ref).state() instanceof TrackerTaskState.Working
        adapter.listReady(50).every { it.ref() != ref }
    }

    // FR9, NFR-R3 of add-base-ref-resolution: the reaper, not the release, is what returns a
    //     released task to circulation — removing the footprint it observes converges the task
    //     to Ready and another instance claims it cleanly
    def "a released task returns to Ready through the reaper, never through the release itself"() {
        given: 'a task this instance claimed through the port and then released'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'release-then-reap fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:release-reaped')
        claimedByA(adapter, ref)
        adapter.release(ref)

        when: 'a reaper removes the claim footprint it observes on the released task'
        def result = adapter.removeStaleClaim(ref, claimFactsOf(adapter, ref))

        then: 'the removal is what converges the task to Ready'
        result instanceof RemoveStaleClaimResult.Removed
        adapter.fetchTask(ref).state() == new TrackerTaskState.Ready()

        and: 'the freed task is claimable by another instance'
        adapter.claim(ref, 'instance-b') instanceof ClaimResult.Acquired
    }

    // FR15 of add-tracker-port, NFR-R2: release is idempotent — a second release of the same
    //     task neither throws nor changes what the first one left
    def "a repeated release is a no-op"() {
        given: 'a task this instance claimed through the port and already released once'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'release idempotence fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:release-twice')
        claimedByA(adapter, ref)
        adapter.release(ref)
        def afterFirst = adapter.fetchTask(ref)

        when: 'the same holder releases again'
        adapter.release(ref)

        then: 'nothing thrown, nothing changed'
        noExceptionThrown()
        adapter.fetchTask(ref) == afterFirst
    }

    /**
     * A Ready fixture claimed through the port by {@code instance-a} — not the {@code
     * seedWorkingWithClaim} seam, which plants a marker without the claim's own record. A release
     * is only ever issued by a holder that really claimed, and what it leaves behind for the
     * reaper (a dead footprint, or the untouched live one) exists only on that path.
     */
    private void claimedByA(Tracker adapter, TaskRef ref) {
        seedTask(adapter, ref, new TrackerTaskState.Ready(), AbortFacts.none())
        assert adapter.claim(ref, 'instance-a') instanceof ClaimResult.Acquired
    }
}
