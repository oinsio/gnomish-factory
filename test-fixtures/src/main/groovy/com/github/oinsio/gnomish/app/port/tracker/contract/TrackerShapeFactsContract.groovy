package com.github.oinsio.gnomish.app.port.tracker.contract

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.RepairIndexResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState

/**
 * The fact-reporting and index-repair link of the tracker contract chain (FR19, FR12 of
 * harden-task-branch-contract): every adapter reports the facts core classifies — a working task
 * with no claim footprint is reported rather than omitted, and a ready entry carries the same claim
 * footprint the open listing does — and every adapter's {@code repairIndex} restores a claimless
 * working task to {@code Ready} with a changed-facts no-op.
 *
 * <p>This is the MOST-DERIVED contract class in the chain: a concrete adapter subclass instantiates
 * THIS class. The kill windows of the multi-write sequences are not expressible here — the
 * in-memory reference adapter's writes are atomic — so each adapter whose writes are physically
 * non-atomic covers them in its own suite (github-tracker's index-repair and claim-ordering specs).
 *
 * <p>Implements FR19, FR12 of harden-task-branch-contract.
 */
abstract class TrackerShapeFactsContract extends TrackerEpochContract {

    /**
     * Seeds {@code ref} into the working state with NO claim footprint at all — the state the claim
     * sequence freezes between its working-label write and the claim marker it never posted. The one
     * new seam this link adds: {@code seedTask}'s working branch deliberately seeds a live claim, so
     * it cannot express this window.
     *
     * @param adapter the adapter under test
     * @param ref the task to seed
     */
    protected abstract void seedWorkingWithoutClaim(Tracker adapter, TaskRef ref)

    // FR19: the omission rule is gone — a working-state task carrying no claim footprint is
    //     reported with its facts, because it is the claim sequence's own kill window and the
    //     sweep can only repair what it can see.
    def "a working task with no claim footprint is reported with its facts, never omitted"() {
        given: 'a task in the Working state whose thread carries no claim marker at all'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'claimless working fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:facts-claimless-working')
        seedWorkingWithoutClaim(adapter, ref)

        when:
        def entry = adapter.listOpen().find { it.ref() == ref }

        then: 'the entry is present, its labels say working and its claim footprint is absent'
        entry != null
        entry.facts().labels().working()
        entry.facts().claim() instanceof ClaimFacts.None
        entry.claimVersion() == null
    }

    // FR19: a live claim reported by listOpen carries the holder and the version, as facts — the
    //     same pair every other instance observes.
    def "a claimed task reports a live footprint naming its holder"() {
        given:
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'live footprint fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:facts-live-claim')
        seedWorkingWithClaim(adapter, ref, 'instance-a')

        when:
        def entry = adapter.listOpen().find { it.ref() == ref }

        then:
        entry.facts().claim() instanceof ClaimFacts.Live
        (entry.facts().claim() as ClaimFacts.Live).holder() == 'instance-a'
        (entry.facts().claim() as ClaimFacts.Live).version() == entry.claimVersion()
    }

    // FR19: the ready feed carries the same claim facts, so a ready-labeled task still carrying a
    //     claim marker — the suspension leftover — is visible to the sweep instead of winning races
    //     as a ghost.
    def "a ready entry carries the claim footprint its thread shows"() {
        given: 'a claimed task a human returned to Ready, its claim marker left behind'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'ready claim facts fixture')
        def adapter = tracker.get()
        def cleanRef = new TaskRef('fixture:facts-ready-clean')
        def ghostRef = new TaskRef('fixture:facts-ready-ghost')
        seedTask(adapter, cleanRef, new TrackerTaskState.Ready(), AbortFacts.none())
        seedWorkingWithClaim(adapter, ghostRef, 'instance-a')
        returnToReady(adapter, ghostRef)

        when:
        def entries = adapter.listReady(10)

        then: 'an ordinary ready task reports no footprint at all'
        entries.find { it.ref() == cleanRef }.claim() instanceof ClaimFacts.None

        and: 'the returned one still reports the claim marker its thread carries'
        !(entries.find {
            it.ref() == ghostRef
        }.claim() instanceof ClaimFacts.None)
    }

    // FR19, FR12: the ClaimPending repair — a working-state task with no claim footprint is
    //     restored to Ready, and the caller does not end up holding it.
    def "repairIndex restores a claimless working task to Ready"() {
        given:
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'index repair fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:repair-claim-pending')
        seedWorkingWithoutClaim(adapter, ref)
        def observed = adapter.listOpen().find { it.ref() == ref }.facts()

        when:
        def result = adapter.repairIndex(ref, observed)

        then: 'the task is back in circulation, claimable by the ordinary lease'
        result instanceof RepairIndexResult.Repaired
        adapter.fetchTask(ref).state() == new TrackerTaskState.Ready()
        adapter.listOpen().every { it.ref() != ref }
    }

    // FR19: the convergence property — a repair whose observation no longer holds writes nothing
    //     and reports the current facts, so two reapers repairing the same shape converge.
    def "repairIndex is a safe no-op once the observed facts have moved on"() {
        given: 'the frozen window was observed, but a claim landed before the repair ran'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'index repair no-op fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:repair-noop')
        seedWorkingWithoutClaim(adapter, ref)
        def observed = adapter.listOpen().find { it.ref() == ref }.facts()
        seedWorkingWithClaim(adapter, ref, 'instance-b')

        when:
        def result = adapter.repairIndex(ref, observed)

        then: 'nothing was written and the task is still Working, held by the live claim'
        result instanceof RepairIndexResult.Unchanged
        adapter.fetchTask(ref).state() == new TrackerTaskState.Working('instance-b')
    }
}
