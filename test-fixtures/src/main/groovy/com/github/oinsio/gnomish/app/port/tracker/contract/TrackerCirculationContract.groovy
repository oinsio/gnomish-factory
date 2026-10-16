package com.github.oinsio.gnomish.app.port.tracker.contract

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * How a task re-enters and leaves the ready queue, as every adapter reports it (FR2 of
 * add-tracker-port; FR6, FR7 of add-factory-serve; FR4, NFR-R2 of enforce-finish-terminality): a
 * parked task is offered for a claim only once a human returns it, a returned task is listed in the
 * adapter's own queue order with no prioritization, and two declines racing over one reopened
 * finished task leave it terminal.
 *
 * <p>The last link of the chain {@link TrackerContract} describes: the concrete adapter specs
 * extend this class, so the whole suite runs against every adapter.
 *
 * <p>Implements FR2 of add-tracker-port; FR6, FR7 of add-factory-serve; FR4, NFR-R2 of
 * enforce-finish-terminality.
 */
abstract class TrackerCirculationContract extends TrackerTenureBoundaryContract {

    // FR2 of add-tracker-port: AwaitingHuman is left only by a human — the parked task is absent
    //     from the ready queue until a human returns it, and only then does a claim acquire it
    def "a parked task is offered for a claim only after a human returns it to Ready"() {
        given: 'a claimed task parked for a human decision'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'parked circulation fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:circulation-parked')
        seedWorkingWithClaim(adapter, ref, 'instance-a')
        adapter.park(ref, ParkReason.ESCALATION, 'needs a decision')

        expect: 'parked, and not offered to the feed'
        adapter.fetchTask(ref).state() == new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION)
        adapter.listReady(10).every { it.ref() != ref }

        when: 'a human returns the task to Ready'
        returnToReady(adapter, ref)

        then: 'it is offered again, and a claim acquires it'
        adapter.listReady(10).any { it.ref() == ref }
        adapter.claim(ref, 'instance-b') instanceof ClaimResult.Acquired
        adapter.fetchTask(ref).state() instanceof TrackerTaskState.Working
    }

    // FR7 of add-factory-serve: the adapter reports the returned fact and nothing more — a
    //     returned task keeps its place in the adapter's own queue order; returned-first ordering
    //     is core policy
    def "listReady keeps a returned task in the adapter's own order, never moved ahead of fresh ones"() {
        given: 'two fresh tasks, then one returned by a human after a park'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'returned order fixture')
        def adapter = tracker.get()
        def first = new TaskRef('fixture:circulation-fresh-1')
        def second = new TaskRef('fixture:circulation-fresh-2')
        def returned = new TaskRef('fixture:circulation-returned')
        seedTask(adapter, first, new TrackerTaskState.Ready(), AbortFacts.none())
        seedTask(adapter, second, new TrackerTaskState.Ready(), AbortFacts.none())
        seedWorkingWithClaim(adapter, returned, 'instance-a')
        adapter.park(returned, ParkReason.ESCALATION, 'needs a decision')
        returnToReady(adapter, returned)

        when:
        def entries = adapter.listReady(10).findAll {
            it.ref() in [first, second, returned]
        }

        then: 'the returned task is reported as returned, in the order the tasks entered the tracker'
        entries*.ref() == [first, second, returned]
        entries*.returned() == [false, false, true]
    }

    // FR4, NFR-R2 of enforce-finish-terminality: two instances decline the same reopened finished
    //     task at once — the task ends terminal; a duplicate explanation is the worst outcome
    def "racing declines of one reopened finished task leave it terminal, repetition #repetition"() {
        given: 'a finished task a human reopened'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'decline race fixture')
        def adapter = tracker.get()
        def ref = new TaskRef("fixture:circulation-decline-race-${repetition}")
        seedWorkingWithClaim(adapter, ref, 'instance-a')
        adapter.finish(ref, 'summary: shipped it')
        reopenFinished(adapter, ref)
        def explanation = 'this task is already finished; open a new task for further changes'
        def barrier = new CyclicBarrier(2)

        when: 'two instances decline it, lined up at a shared barrier'
        // shutdownNow(), never close(): a decline that never returns must fail the run within the
        // get() bound, not hang it in an executor close that awaits it.
        def pool = Executors.newVirtualThreadPerTaskExecutor()
        try {
            (1..2).collect {
                pool.submit({
                    barrier.await(5, TimeUnit.SECONDS)
                    adapter.declineFinished(ref, explanation)
                    null
                } as Callable<Object>)
            }.each { it.get(3, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        then: 'the task is terminal and out of the ready queue'
        adapter.fetchTask(ref).state() == new TrackerTaskState.Finished()
        adapter.listReady(10).every { it.ref() != ref }

        and: 'the explanation was posted once, or at worst twice'
        postedTexts(adapter, ref).count { it.contains(explanation) } in [1, 2]

        where:
        repetition << (1..3)
    }
}
