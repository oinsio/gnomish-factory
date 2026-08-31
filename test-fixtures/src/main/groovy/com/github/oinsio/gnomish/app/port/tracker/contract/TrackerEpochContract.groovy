package com.github.oinsio.gnomish.app.port.tracker.contract

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState

/**
 * The claim-token properties of the {@link Tracker} port contract suite (tracker-port spec,
 * "Claim issues a monotonic claim token"; FR13 of harden-task-branch-contract): a successful claim
 * returns an epoch, successive tenures of one task are strictly increasing, and any instance
 * reading the task's claim facts obtains the same epoch its holder was issued.
 *
 * <p>Each adapter picks its own monotonic source — the GitHub adapter uses the tracker-assigned
 * claim comment id, the in-memory reference its own rising sequence — so these properties are
 * stated over order alone and never over the token's structure.
 *
 * <p>The last link of the contract chain: both concrete adapter specs extend THIS class, so the
 * whole suite runs against both (M1).
 *
 * <p>Implements FR13 of harden-task-branch-contract.
 */
abstract class TrackerEpochContract extends TrackerFinishContract {

    // FR13, tracker-port "Reclaim returns a greater token": a task claimed, reaped, and claimed
    //     again — by any instance — issues an epoch strictly greater than the tenure it replaced,
    //     which is what lets a reader call the older tenure's artifacts stale
    def "a reclaim after a reap is issued a strictly greater epoch"() {
        given: 'a task held by instance-a, and the claim version a reaper observed'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'claim epoch monotonicity fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:epoch-reclaim')
        seedWorkingWithClaim(adapter, ref, 'instance-a')
        ClaimVersion first = claimVersionOf(adapter, ref)
        def firstFootprint = claimFactsOf(adapter, ref)

        when: 'the reaper returns the task to circulation and another instance claims it'
        adapter.removeStaleClaim(ref, firstFootprint)
        def reclaim = adapter.claim(ref, 'instance-b')

        then: 'the reclaim succeeded and carries an epoch strictly greater than the first tenure'
        reclaim instanceof ClaimResult.Acquired
        ((ClaimResult.Acquired) reclaim).epoch() > first.epoch()
    }

    // FR13, tracker-port "Token is observable by other instances": the epoch a holder was issued
    //     is the epoch every other instance reads out of the task's claim facts — otherwise a
    //     reader could not tell a live tenure's artifacts from a superseded one's
    def "the epoch a claim was issued is the epoch its claim facts report"() {
        given: 'a Ready task no instance holds'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'claim epoch observability fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:epoch-observable')
        seedTask(adapter, ref, new TrackerTaskState.Ready(), AbortFacts.none())

        when: 'one instance claims it and another reads the open listing'
        def claim = adapter.claim(ref, 'instance-a')

        then: 'the claim succeeded'
        claim instanceof ClaimResult.Acquired

        and: 'the listing reports the very epoch the holder was issued'
        claimVersionOf(adapter, ref).epoch() == ((ClaimResult.Acquired) claim).epoch()
    }

    // FR13: a beat refreshes the version without beginning a new tenure, so the epoch stands still
    //     while updatedAt moves — the property that lets the epoch mean "which tenure", not "when"
    def "a beat leaves the epoch unchanged"() {
        given: 'a Working task holding a live claim'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'claim epoch beat fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:epoch-beat')
        seedWorkingWithClaim(adapter, ref, 'instance-a')
        ClaimVersion before = claimVersionOf(adapter, ref)

        when: 'the holder beats its claim'
        adapter.heartbeat(ref, 'stage=build attempt=1 alive-at=now')
        ClaimVersion after = claimVersionOf(adapter, ref)

        then: 'the version moved on but the tenure did not'
        after != before
        after.epoch() == before.epoch()
    }

    /**
     * The task's live claim version, read port-agnostically off {@code listOpen} — the same
     * reader {@link TrackerReapContract} keeps, repeated here so this most-derived class does not
     * depend on a sibling link's private method.
     */
    private static ClaimVersion claimVersionOf(Tracker adapter, TaskRef ref) {
        OpenTask entry = adapter.listOpen().find { it.ref() == ref }
        assert entry?.claimVersion() != null
        entry.claimVersion()
    }
}
