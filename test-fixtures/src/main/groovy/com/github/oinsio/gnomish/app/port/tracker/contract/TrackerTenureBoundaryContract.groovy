package com.github.oinsio.gnomish.app.port.tracker.contract

import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant

/**
 * The tenure boundary {@code fetchTask} reports, as every adapter reports it (FR1 of
 * fix-terminal-receipt-convergence, design D1): the kind of the thread's last {@code PARK} /
 * {@code FINISH} / {@code ABORT} / {@code STALE_CLAIM_REMOVED} marker, whatever claims follow it.
 * A receipt probe reads it after the pickup's own claim, so the property that matters is that a
 * claim does not erase it — the claim-scoped boundary of the shape facts is null by then, which is
 * the defect this fact replaces.
 *
 * <p>Uses the seams the chain already owns: {@code returnToReady} as the human's return, and the
 * port's own writes for every boundary. A second return pulls the reclaimed task back to Ready and
 * the same holder claims again, which every adapter grants without writing a boundary — a claim by
 * another instance would lose to the live one on an adapter that races claims in a window.
 *
 * <p>Implements FR1 of fix-terminal-receipt-convergence.
 */
abstract class TrackerTenureBoundaryContract extends TrackerRepairContract {

    // FR1: the park is the thread's last boundary, so the claim that follows the human's return —
    //     and a second return and claim after it — leave it the reported tenure boundary
    def "the tenure boundary stays PARK across the claims that follow the human's return"() {
        given: 'a claimed task parked for a human, returned to Ready, and claimed by instance B'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'tenure boundary survival fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:tenure-park-survives')
        seedWorkingWithClaim(adapter, ref, 'instance-a')
        adapter.park(ref, ParkReason.ESCALATION, 'needs a decision')
        returnToReady(adapter, ref)
        assert adapter.claim(ref, 'instance-b') instanceof ClaimResult.Acquired

        expect: 'the new holder, and the park as the tenure boundary'
        adapter.fetchTask(ref).state() == new TrackerTaskState.Working('instance-b')
        adapter.fetchTask(ref).tenureBoundary() == BoundaryKind.PARK

        when: 'a second return, and a second claim'
        returnToReady(adapter, ref)
        def second = adapter.claim(ref, 'instance-b')

        then: 'the claim is granted and the park is still the tenure boundary'
        second instanceof ClaimResult.Acquired
        adapter.fetchTask(ref).state() == new TrackerTaskState.Working('instance-b')
        adapter.fetchTask(ref).tenureBoundary() == BoundaryKind.PARK
    }

    // FR1: a claim that died without a park is reaped, and the reaper's removal is the boundary the
    //     next claim leaves in place — never a park
    def "a reaped claim leaves STALE_CLAIM_REMOVED as the tenure boundary after the next claim"() {
        given: 'a working task whose claim the reaper removed'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'tenure boundary reaped fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:tenure-reaped')
        seedWorkingWithClaim(adapter, ref, 'instance-a')
        adapter.removeStaleClaim(ref, claimFactsOf(adapter, ref))

        when: 'instance B claims it'
        def claimed = adapter.claim(ref, 'instance-b')

        then: 'B holds it, and the tenure boundary is the removal'
        claimed instanceof ClaimResult.Acquired
        adapter.fetchTask(ref).state() == new TrackerTaskState.Working('instance-b')
        adapter.fetchTask(ref).tenureBoundary() == BoundaryKind.STALE_CLAIM_REMOVED
    }

    // FR1: each session-ending write is reported as its own kind, and a task no boundary ended
    //     reports none
    def "fetchTask reports #expected as the tenure boundary after #write"() {
        given: 'a task claimed by instance A'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'tenure boundary kinds fixture')
        def adapter = tracker.get()
        def ref = new TaskRef("fixture:tenure-kind-${write.replace(' ', '-')}")
        seedWorkingWithClaim(adapter, ref, 'instance-a')

        when: 'the write ends the tenure'
        action(adapter, ref)

        then:
        adapter.fetchTask(ref).tenureBoundary() == expected

        where:
        write | expected | action
        'no write' | null | { Tracker t, TaskRef r -> }
        'park' | BoundaryKind.PARK | { Tracker t, TaskRef r ->
            t.park(r, ParkReason.ESCALATION, 'needs a decision')
        }
        'finish' | BoundaryKind.FINISH | { Tracker t, TaskRef r ->
            t.finish(r, 'summary: shipped it')
        }
        'abort' | BoundaryKind.ABORT | { Tracker t, TaskRef r ->
            t.recordAbort(r, abortBy('instance-a'))
        }
        'reaper removal' | BoundaryKind.STALE_CLAIM_REMOVED | { Tracker t, TaskRef r ->
            t.removeStaleClaim(r, claimFactsOf(t, r))
        }
    }

    protected static AbortRecord abortBy(String holder) {
        new AbortRecord(UntrustedText.subprocess('infra hiccup'), holder, Instant.parse('2026-07-20T10:00:00Z'))
    }
}
