package com.github.oinsio.gnomish.app.port.tracker.contract

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState

/**
 * Lease-maintenance properties of the {@link Tracker} port contract suite
 * (tracker-port spec, "Open-task listing with claim versions" and the
 * heartbeat / stale-claim-removal requirements; tasks 2.1–2.3, FR5, FR8,
 * NFR-R2 of add-claim-heartbeat). Extends {@link TrackerFetchContract} to
 * reuse its {@code arrange}/{@code seedTask}/{@code seedReply} seams rather
 * than duplicating them; a concrete adapter subclass instantiates THIS class
 * (not {@link TrackerContract}, {@link TrackerMarkerContract}, or {@link
 * TrackerFetchContract} directly) to run the full suite, per M1 — the same
 * suite against every adapter. Kept in a fourth file because the combined
 * property set of the whole chain would exceed the project's per-file line
 * cap.
 *
 * <p>No concrete adapter spec extends this class yet: the in-memory reference
 * is wired in task 2.4 and the GitHub adapter in task 3.5, once their
 * adapters implement {@code listOpen}/{@code heartbeat}/{@code removeStaleClaim}
 * and the {@link #seedWorkingWithClaim} seam below. Until then this abstract
 * spec is never instantiated and therefore does not run — intentional, and it
 * keeps the build green for both adapters between tasks.
 *
 * <p>This file covers task 2.1: {@code listOpen} filtering and claim-version
 * carry ("Listing carries versions"). Tasks 2.2 (heartbeat) and 2.3
 * ({@code removeStaleClaim}) append their properties to this same class, or to
 * a further {@code extends} file if the line cap forces a split.
 *
 * <p>Implements FR5 of add-claim-heartbeat.
 */
abstract class TrackerLeaseContract extends TrackerFetchContract {

    /**
     * Seeds one fixture task at {@code Working(holder)} WITH a live claim
     * marker, so {@code listOpen} resolves a non-null {@link
     * com.github.oinsio.gnomish.app.port.tracker.ClaimVersion} for it. This is a
     * distinct seam from {@link #seedTask}: the base {@code seedTask} guarantees
     * only that the logical {@code Working} state round-trips through {@code
     * fetchTask}, NOT that a claim marker with a resolvable version exists — a
     * {@code Working}-labeled task whose claim marker is missing is a legitimate
     * state the port reports with an ABSENT (null) claim (github-tracker:
     * "missing claim comment → absent claim"). Only an explicit claim marker
     * carries a version, so the version-carry properties need this dedicated
     * seam. Implementers (in-memory task 2.4, GitHub task 3.5) MUST create the
     * adapter's actual claim marker (the in-memory claim record, a GitHub claim
     * comment, ...) so that {@code listOpen} reports {@code Working(holder)} with
     * a non-null claim version read back from the marker.
     *
     * @param adapter the tracker adapter arranged by {@link #arrange}
     * @param ref the fixture task's identity
     * @param holder the claiming instance's identifier written into the marker
     */
    protected abstract void seedWorkingWithClaim(Tracker adapter, TaskRef ref, String holder)

    // FR5: listOpen returns exactly the open tasks — Working and AwaitingHuman —
    //     never Ready/Finished/Gone; the Working entry carries holder and a
    //     non-null claim version, the AwaitingHuman entry a null version ("Listing
    //     carries versions")
    def "listOpen returns only open tasks, Working with holder and version, AwaitingHuman without"() {
        given: 'a tracker seeded with one task per logical state, the Working one holding a claim'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'listOpen filtering fixture')
        def adapter = tracker.get()
        def workingRef = new TaskRef('fixture:open-working')
        def awaitingRef = new TaskRef('fixture:open-awaiting-human')
        seedWorkingWithClaim(adapter, workingRef, 'instance-a')
        seedTask(adapter, awaitingRef, new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION), AbortFacts.none())
        seedTask(adapter, new TaskRef('fixture:open-ready'), new TrackerTaskState.Ready(), AbortFacts.none())
        seedTask(adapter, new TaskRef('fixture:open-finished'), new TrackerTaskState.Finished(), AbortFacts.none())
        seedTask(adapter, new TaskRef('fixture:open-gone'), new TrackerTaskState.Gone(), AbortFacts.none())

        when: 'listOpen is called'
        List<OpenTask> result = adapter.listOpen()

        then: 'exactly the two open tasks come back — never the Ready, Finished, or Gone task'
        result*.ref() as Set == [workingRef, awaitingRef] as Set

        and: 'the Working entry carries its holder via the state and a non-null claim version'
        def working = result.find { it.ref() == workingRef }
        working.state() == new TrackerTaskState.Working('instance-a')
        working.claimVersion() != null

        and: 'the AwaitingHuman entry carries its reason and a null claim version — no claim'
        def awaiting = result.find { it.ref() == awaitingRef }
        awaiting.state() == new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION)
        awaiting.claimVersion() == null
    }

    // FR5, FR4: listOpen and listReady partition the feed — a Ready task shows in
    //     listReady and never in listOpen; a Working task shows in listOpen and never
    //     in listReady — so listReady's "only Ready tasks" contract still holds
    def "listOpen and listReady partition Ready and Working tasks"() {
        given: 'a tracker seeded with one Ready task and one Working task holding a claim'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'listOpen/listReady partition fixture')
        def adapter = tracker.get()
        def readyRef = new TaskRef('fixture:partition-ready')
        def workingRef = new TaskRef('fixture:partition-working')
        seedTask(adapter, readyRef, new TrackerTaskState.Ready(), AbortFacts.none())
        seedWorkingWithClaim(adapter, workingRef, 'instance-a')

        when: 'both feeds are read'
        def ready = adapter.listReady(10)*.ref()
        def open = adapter.listOpen()*.ref()

        then: 'the Ready task is in listReady only, the Working task in listOpen only'
        ready.contains(readyRef)
        !ready.contains(workingRef)
        open.contains(workingRef)
        !open.contains(readyRef)
    }
}
