package com.github.oinsio.gnomish.app.port.tracker.contract

import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Stale-claim-removal properties of the {@link Tracker} port contract suite
 * (tracker-port spec, "Stale-claim removal returns the task to circulation" and
 * the "Concurrent removal race" scenario of "Contract suite covers lease
 * maintenance"; task 2.3, FR4, FR5, NFR-R2 of add-claim-heartbeat). Extends
 * {@link TrackerHeartbeatContract} to reuse its {@code arrange}/{@code
 * seedTask}/{@code seedWorkingWithClaim} seams rather than duplicating them.
 *
 * <p>This is the MOST-DERIVED contract class in the lease chain
 * ({@code TrackerContract → TrackerMarkerContract → TrackerFetchContract →
 * TrackerLeaseContract → TrackerHeartbeatContract → TrackerReapContract}): a
 * concrete adapter subclass instantiates THIS class — the in-memory reference in
 * task 2.4 and the GitHub adapter in task 3.5 — so the SAME whole suite runs
 * against every adapter, per M1. Kept in a sixth file because the combined
 * property set of the whole chain would exceed the project's per-file line cap.
 *
 * <p>No concrete adapter spec extends this class yet: it is wired once the
 * adapters implement {@code removeStaleClaim} and the {@code seedWorkingWithClaim}
 * seam. Until then this abstract spec is never instantiated and therefore does
 * not run — intentional, and it keeps the build green for both adapters between
 * tasks.
 *
 * <p>"Transition marker recoverable" is asserted port-agnostically, by its
 * observable effect rather than by reaching into an adapter's marker storage: the
 * task returns to {@code Ready} cleanly, the removed claim is gone, and a fresh
 * claim by another instance succeeds with a version distinct from the removed one
 * — proof the dead lease was cleared and a new lease round can start past the
 * transition. That avoids a marker-content seam here; asserting the physical
 * boundary marker's anchoring is left to the GitHub adapter's own specs
 * (github-tracker: "Marker anchors the next lease round"), task 3.3/3.4.
 *
 * <p>Implements FR4, FR5, NFR-R2 of add-claim-heartbeat.
 */
abstract class TrackerReapContract extends TrackerHeartbeatContract {

    // FR4, FR5: removeStaleClaim on a matching live version returns the task to
    //     circulation as ONE operation — result Removed, task back to Ready, the
    //     dead claim gone; the holder transition is recoverable, observed
    //     port-agnostically as a fresh claim by another instance succeeding with a
    //     version distinct from the removed one ("Removal round-trip")
    def "removeStaleClaim round-trips a stale claim back to Ready, recoverable by a fresh claim"() {
        given: 'a Working task holding a live claim, its version read via listOpen'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'removeStaleClaim round-trip fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:reap-round-trip')
        seedWorkingWithClaim(adapter, ref, 'instance-a')
        ClaimVersion dead = claimVersionOf(adapter, ref)
        def deadFootprint = claimFactsOf(adapter, ref)

        when: 'a reaper removes the stale claim against the footprint it observed'
        def result = adapter.removeStaleClaim(ref, deadFootprint)

        then: 'the removal succeeds as one operation'
        result instanceof RemoveStaleClaimResult.Removed

        and: 'the task is back to Ready and no longer appears among the open tasks'
        adapter.fetchTask(ref).state() == new TrackerTaskState.Ready()
        adapter.listOpen().every { it.ref() != ref }

        and: 'the transition is recoverable: another instance claims the freed task cleanly, its version distinct from the removed one'
        adapter.claim(ref, 'instance-b') instanceof ClaimResult.Acquired
        claimVersionOf(adapter, ref) != dead
    }

    // FR5, NFR-R2: removeStaleClaim with a version the holder has since beaten is a
    //     safe no-op — result Mismatch carrying the LIVE version (non-null, equal to
    //     the post-beat version), and the task stays Working, nothing removed
    //     ("Version mismatch is a no-op")
    def "removeStaleClaim with a beaten version is a no-op reporting the live claim"() {
        given: 'a Working task whose holder beats its claim after the reaper read the old version'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'removeStaleClaim mismatch fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:reap-mismatch')
        seedWorkingWithClaim(adapter, ref, 'instance-a')
        ClaimVersion stale = claimVersionOf(adapter, ref)
        def staleFootprint = claimFactsOf(adapter, ref)
        adapter.heartbeat(ref, 'stage=build attempt=1 alive-at=now')
        ClaimVersion live = claimVersionOf(adapter, ref)
        assert live != stale

        when: 'the reaper tries to remove the claim against the now-stale footprint'
        def result = adapter.removeStaleClaim(ref, staleFootprint)

        then: 'nothing is removed — the result reports the live claim, non-null and equal to the post-beat version'
        result instanceof RemoveStaleClaimResult.Mismatch
        (result as RemoveStaleClaimResult.Mismatch).currentVersion() == live

        and: 'the task is still Working, held by the same instance'
        adapter.fetchTask(ref).state() == new TrackerTaskState.Working('instance-a')
    }

    // FR4, FR5, NFR-R2: two reapers racing to remove the SAME stale claim converge
    //     — both calls return without error, the task ends Ready exactly once, and
    //     the outcomes are coherent (at least one Removed; any other a harmless
    //     Removed or Mismatch) ("Concurrent removal race"); repeated to rule out a
    //     race that merely appeared to pass once (M2)
    def "removeStaleClaim converges under a concurrent removal race, repetition #repetition"() {
        given: 'a Working task holding a live claim, its version read by both reapers'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'removeStaleClaim race fixture')
        def adapter = tracker.get()
        def ref = new TaskRef("fixture:reap-race-${repetition}")
        seedWorkingWithClaim(adapter, ref, 'instance-a')
        def observed = claimFactsOf(adapter, ref)
        def reaperCount = 2
        def barrier = new CyclicBarrier(reaperCount)

        when: 'both reapers call removeStaleClaim for the same observed version, lined up at a shared barrier'
        // shutdownNow(), never close(): mirrors the claim-race property — a call
        // that fails to release its store lock would park the other reaper forever,
        // and close() would await it and hang the run instead of failing it. The 3s
        // get() bound keeps that inside PIT's default per-mutation budget; the
        // critical section itself is microseconds.
        List<RemoveStaleClaimResult> results = []
        def pool = Executors.newVirtualThreadPerTaskExecutor()
        try {
            def futures = (1..reaperCount).collect {
                pool.submit({
                    barrier.await(5, TimeUnit.SECONDS)
                    adapter.removeStaleClaim(ref, observed)
                } as Callable<RemoveStaleClaimResult>)
            }
            results = futures.collect { it.get(3, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        then: 'both calls returned without error, and the task ends Ready — the removal converged exactly once'
        results.size() == reaperCount
        adapter.fetchTask(ref).state() == new TrackerTaskState.Ready()

        and: 'the outcomes are coherent: at least one Removed, any other a harmless Removed or Mismatch'
        results.any { it instanceof RemoveStaleClaimResult.Removed }
        results.every {
            it instanceof RemoveStaleClaimResult.Removed || it instanceof RemoveStaleClaimResult.Mismatch
        }

        where:
        repetition << (1..5)
    }

    // FR4, FR5, NFR-R2: removeStaleClaim against a task the tracker no longer holds at
    //     all is a safe no-op reporting Mismatch(null) — the claim is gone with its
    //     task — never a thrown exception. Symmetric to the heartbeat "unknown task"
    //     property: in-memory returns Mismatch(null) instead of NoSuchTrackedTaskException,
    //     GitHub maps the issue's 404 re-read to Mismatch(null) rather than an infra failure,
    //     so a foreign reaper observing a vanished task converges without burning a retry
    def "removeStaleClaim against a task the tracker does not know is a no-op reporting Mismatch(null)"() {
        given: 'a tracker with NO fixture task seeded for this ref'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'removeStaleClaim unknown-task fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:reap-unknown')
        // The task does not exist, so there is no live version to read back through the port
        // (the reader helpers below would find nothing). The guard is irrelevant here — an
        // absent task can match no version — so a synthetic observed version is passed purely
        // to satisfy the never-null parameter; the outcome is Mismatch(null) regardless of it.
        def observed = new ClaimFacts.Live(
                'never-existed-holder', new ClaimVersion('never-existed', Instant.EPOCH, new ClaimEpoch(1)))

        when: 'the reaper targets a task the tracker has never heard of'
        def result = adapter.removeStaleClaim(ref, observed)

        then: 'a safe no-op — the current claim is reported absent (null), never thrown'
        result == new RemoveStaleClaimResult.Mismatch(null)
    }

    /**
     * Reads the current live {@link ClaimVersion} of {@code ref} back from {@code
     * listOpen} — the same fact any other instance observes — so these properties
     * never construct a version literal but always read one through the port, per
     * {@link ClaimVersion}'s opaque contract. A private counterpart to {@link
     * TrackerHeartbeatContract}'s reader, kept local so this most-derived class
     * does not rely on calling a sibling link's private method.
     */
    private static ClaimVersion claimVersionOf(Tracker adapter, TaskRef ref) {
        OpenTask entry = adapter.listOpen().find { it.ref() == ref }
        assert entry?.claimVersion() != null
        entry.claimVersion()
    }
}
