package com.github.oinsio.gnomish.app.port.tracker.contract

import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.RepairIndexResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState

/**
 * The lease-maintenance repairs every adapter owes beyond the plain removal round-trip (FR19, FR12
 * of harden-task-branch-contract): a dead claim footprint — a tenure that left a trace but no live
 * version — is removable like a live one, and {@code repairIndex} completes the transition a
 * boundary marker implies while the indexed state still reads working. In both repairs the caller
 * ends up holding nothing: the restored task is claimable by anyone, the finished one is offered to
 * no one. Before this link the two properties were pinned per adapter only (F10 of
 * tighten-spec-contract); here the same suite runs them against every adapter.
 *
 * <p>Two seams, because the frozen states are physical and each adapter freezes them its own way:
 * a dead footprint is a cleared claim marker in the reference adapter and a claim comment a later
 * boundary voided on GitHub; an indexed state rewound under a boundary is a state edit in the one
 * and a label edit in the other.
 *
 * <p>Implements FR19, FR12 of harden-task-branch-contract.
 */
abstract class TrackerRepairContract extends TrackerReleaseContract {

    /**
     * Seeds {@code ref} as a working task whose claim footprint is dead: {@code holder}'s tenure left
     * its trace on the task, and no live claim version backs it.
     *
     * @param adapter the adapter under test
     * @param ref the task to seed
     * @param holder the instance the dead footprint names
     */
    protected abstract void seedWorkingWithDeadClaim(Tracker adapter, TaskRef ref, String holder)

    /**
     * Moves {@code ref}'s indexed state back to working out of band, touching no recorded history —
     * the frozen state a kill leaves between a boundary marker and the flip it implies.
     *
     * @param adapter the adapter under test
     * @param ref an already-seeded task
     */
    protected abstract void rewindIndexToWorking(Tracker adapter, TaskRef ref)

    // FR19: a dead footprint is an eligible removal input — the reaper's observation is the
    //     footprint itself, with no live version to compare, and the removal still returns the task
    //     to circulation (F10 of tighten-spec-contract)
    def "removeStaleClaim retires a dead footprint that no live version backs"() {
        given: 'a working task whose claim footprint is dead'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'dead footprint fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:repair-dead-footprint')
        seedWorkingWithDeadClaim(adapter, ref, 'instance-a')
        def observed = claimFactsOf(adapter, ref)
        assert observed == new ClaimFacts.Dead('instance-a')

        when: 'a reaper removes the footprint it observed'
        def result = adapter.removeStaleClaim(ref, observed)

        then: 'the removal acts, and the task is back in circulation'
        result instanceof RemoveStaleClaimResult.Removed
        adapter.fetchTask(ref).state() == new TrackerTaskState.Ready()
        adapter.listOpen().every { it.ref() != ref }

        and: 'another instance claims it cleanly'
        adapter.claim(ref, 'instance-b') instanceof ClaimResult.Acquired
    }

    // FR12: the IndexLagging repair — the flip a finish marker implies is completed, and the
    //     finished work is offered to no one again (F10 of tighten-spec-contract)
    def "repairIndex completes a finish marker's transition and never offers the finished work again"() {
        given: 'a finished task whose indexed state was rewound to working under its finish marker'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'index lagging fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:repair-index-lagging')
        seedWorkingWithClaim(adapter, ref, 'instance-a')
        adapter.finish(ref, 'summary: shipped it')
        rewindIndexToWorking(adapter, ref)
        def observed = adapter.listOpen().find { it.ref() == ref }.facts()
        assert observed.latestBoundary() == BoundaryKind.FINISH

        when:
        def result = adapter.repairIndex(ref, observed)

        then: 'the transition the marker implies is completed'
        result instanceof RepairIndexResult.Repaired
        adapter.fetchTask(ref).state() == new TrackerTaskState.Finished()

        and: 'the task is neither open nor offered for a claim: its work is not executed again'
        adapter.listOpen().every { it.ref() != ref }
        adapter.listReady(10).every { it.ref() != ref }
    }

    // FR12: the ClaimPending rollback restores the task without a claim on it — no caller ends up
    //     holding the task the repair returned to circulation
    def "repairIndex leaves no claim on the task it restores to Ready"() {
        given: 'a working task with no claim footprint at all'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'claim pending fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:repair-no-claim-left')
        seedWorkingWithoutClaim(adapter, ref)
        def observed = adapter.listOpen().find { it.ref() == ref }.facts()

        when:
        adapter.repairIndex(ref, observed)

        then: 'the ready entry carries no claim footprint'
        adapter.listReady(10).find {
            it.ref() == ref
        }.claim() instanceof ClaimFacts.None

        and: 'the first claim after the repair is acquired, so nobody held the task before it'
        adapter.claim(ref, 'instance-b') instanceof ClaimResult.Acquired
    }
}
