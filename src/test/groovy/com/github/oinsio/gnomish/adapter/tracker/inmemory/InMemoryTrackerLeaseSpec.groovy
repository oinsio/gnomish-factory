package com.github.oinsio.gnomish.adapter.tracker.inmemory

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.time.Instant

/**
 * Implementation-detail properties of {@link InMemoryTracker}'s lease
 * maintenance (add-claim-heartbeat, FR4/FR5/FR8) that the adapter-agnostic
 * {@code TrackerReapContract} suite cannot force deterministically: the exact
 * beat-between-observation-and-removal interleaving and double-removal race
 * driven through {@link InMemoryTrackerHarness#armRemoveStaleClaimGate}, the
 * claim-marker lifecycle across every {@code Working}-leaving operation, the
 * stored heartbeat payload and thread narration, monotonic version advance, and
 * the store-lock release each new operation must perform on exit (design D15).
 *
 * <p>Implements FR4, FR5, FR8 of add-claim-heartbeat.
 */
class InMemoryTrackerLeaseSpec extends AbstractInMemoryTrackerSpec {

    private InMemoryTracker tracker
    private InMemoryTrackerHarness harness

    def setup() {
        tracker = new InMemoryTracker()
        harness = new InMemoryTrackerHarness(tracker)
    }

    // FR5: heartbeat refreshes the marker in place, stores the payload, and narrates the beat
    def "heartbeat stores the payload on the claim marker and narrates a HEARTBEAT entry"() {
        given: 'a Working task holding a live claim'
        def ref = new TaskRef('fixture:beat-payload')
        harness.seedWorkingWithClaim(tracker, ref, 'instance-a')

        when: 'the holder beats its claim'
        def result = tracker.heartbeat(ref, 'stage=build attempt=1')

        then: 'the beat succeeds and the payload is stored on the marker'
        result instanceof HeartbeatResult.Beaten
        tracker.store.get(ref).claimMarker().payload() == 'stage=build attempt=1'

        and: 'the thread carries a HEARTBEAT entry whose text narrates the payload'
        def thread = harness.thread(ref)
        thread*.kind() == [
            CorrespondenceEntry.Kind.HEARTBEAT
        ]
        thread[0].text().contains('stage=build attempt=1')
    }

    // FR5, FR8: once the marker is cleared by a Working-leaving op, a beat reports ClaimGone
    //     (never throws) — one row per op that clears the marker: park, finish, recordAbort, release
    def "heartbeat after #op clears the marker reports ClaimGone without throwing"() {
        given: 'a Working task holding a live claim'
        def ref = new TaskRef("fixture:beat-gone-${op}")
        harness.seedWorkingWithClaim(tracker, ref, 'instance-a')

        when: 'a Working-leaving operation runs, then the former holder beats'
        clearer.call(tracker, ref)
        def result = tracker.heartbeat(ref, 'stage=build')

        then: 'the beat reports the claim is gone, a signal rather than an exception'
        result instanceof HeartbeatResult.ClaimGone

        and: 'the marker is truly cleared'
        tracker.store.get(ref).claimMarker() == null

        where:
        op          | clearer
        'park'      | { t, r -> t.park(r, ParkReason.CHECKPOINT, 'paused') }
        'finish'    | { t, r -> t.finish(r, 'done') }
        'abort'     | { t, r -> t.recordAbort(r, new AbortRecord('boom', 'instance-a', Instant.parse('2026-07-20T10:00:00Z'))) }
        'release'   | { t, r -> t.release(r) }
    }

    // FR5: listOpen still reports a marker-cleared task that stays open, with a null version —
    //     park (-> AwaitingHuman) and release (-> stays Working) both drop the claim version
    def "listOpen reports #op as open with a null claim version after the marker is cleared"() {
        given: 'a Working task holding a live claim'
        def ref = new TaskRef("fixture:open-null-${op}")
        harness.seedWorkingWithClaim(tracker, ref, 'instance-a')

        when: 'the marker-clearing op runs'
        clearer.call(tracker, ref)

        then: 'the task is still open but carries no claim version'
        def entry = tracker.listOpen().find { it.ref() == ref }
        entry != null
        entry.claimVersion() == null

        where:
        op        | clearer
        'park'    | { t, r -> t.park(r, ParkReason.CHECKPOINT, 'paused') }
        'release' | { t, r -> t.release(r) }
    }

    // FR5: successive claims mint distinct marker identities and strictly advancing versions
    def "successive claims mint distinct marker ids and advancing versions"() {
        given: 'two Ready tasks'
        def refA = new TaskRef('fixture:advance-a')
        def refB = new TaskRef('fixture:advance-b')
        harness.seed(refA, new TaskSnapshot(refA.id(), 't', 'b'), new TrackerTaskState.Ready(), AbortFacts.none())
        harness.seed(refB, new TaskSnapshot(refB.id(), 't', 'b'), new TrackerTaskState.Ready(), AbortFacts.none())

        when: 'both are claimed'
        tracker.claim(refA, 'instance-a')
        tracker.claim(refB, 'instance-b')
        def markerA = tracker.store.get(refA).claimMarker()
        def markerB = tracker.store.get(refB).claimMarker()

        then: 'the marker identities differ and the versions advance'
        markerA.markerId() != markerB.markerId()
        markerA.updatedAt() != markerB.updatedAt()
    }

    // FR4, FR5: a beat surviving through the marker carries the holder, so a later matching
    //     removal names the dead holder and returns the task to Ready
    def "removeStaleClaim after a beat removes on the live version and names the dead holder"() {
        given: 'a Working task whose holder has beaten its claim'
        def ref = new TaskRef('fixture:remove-after-beat')
        harness.seedWorkingWithClaim(tracker, ref, 'instance-a')
        tracker.heartbeat(ref, 'stage=build')
        ClaimVersion live = claimVersionOf(ref)

        when: 'a reaper removes against the live post-beat version'
        def result = tracker.removeStaleClaim(ref, live)

        then: 'the removal succeeds, returns the task to Ready, and narrates the dead holder'
        result instanceof RemoveStaleClaimResult.Removed
        tracker.fetchTask(ref).state() == new TrackerTaskState.Ready()
        def removal = harness.thread(ref).find { it.kind() == CorrespondenceEntry.Kind.STALE_CLAIM_REMOVED }
        removal.text().contains('instance-a')
    }

    // FR5, NFR-R2: a second removal of an already-removed claim is a safe no-op reporting a null
    //     version — the marker==null branch that makes concurrent reapers converge
    def "removeStaleClaim a second time reports Mismatch with a null version"() {
        given: 'a Working task whose claim a reaper has already removed'
        def ref = new TaskRef('fixture:double-remove')
        harness.seedWorkingWithClaim(tracker, ref, 'instance-a')
        ClaimVersion observed = claimVersionOf(ref)
        tracker.removeStaleClaim(ref, observed)

        when: 'a second reaper removes against the same now-dead version'
        def result = tracker.removeStaleClaim(ref, observed)

        then: 'the second removal is a no-op reporting a null current version'
        result instanceof RemoveStaleClaimResult.Mismatch
        (result as RemoveStaleClaimResult.Mismatch).currentVersion() == null
    }

    // FR5: the removal gate proves the version is re-checked UNDER the lock, after any interleaving —
    //     a beat landing between the reaper's observation and its removal turns the removal into a
    //     no-op reporting the live post-beat version, and the task stays Working
    def "a beat armed on the removal gate lands before the under-lock re-check, forcing a Mismatch"() {
        given: 'a Working task holding a live claim, its version observed by a reaper'
        def ref = new TaskRef('fixture:beat-between')
        harness.seedWorkingWithClaim(tracker, ref, 'instance-a')
        ClaimVersion observed = claimVersionOf(ref)
        harness.armRemoveStaleClaimGate { tracker.heartbeat(ref, 'sneaky beat') }

        when: 'the reaper removes against its stale observation'
        def result = tracker.removeStaleClaim(ref, observed)

        then: 'nothing is removed — the live post-beat version is reported, and the task stays Working'
        result instanceof RemoveStaleClaimResult.Mismatch
        (result as RemoveStaleClaimResult.Mismatch).currentVersion() == claimVersionOf(ref)
        tracker.fetchTask(ref).state() == new TrackerTaskState.Working('instance-a')
    }

    // FR4, FR5, NFR-R2: two removals of the SAME version converge deterministically — a nested
    //     removal armed on the gate removes first, so the outer removal finds the claim already gone
    def "a competing removal armed on the gate converges: first Removed, outer Mismatch"() {
        given: 'a Working task holding a live claim, observed by both reapers'
        def ref = new TaskRef('fixture:remove-race')
        harness.seedWorkingWithClaim(tracker, ref, 'instance-a')
        ClaimVersion observed = claimVersionOf(ref)
        def firstResult = null
        harness.armRemoveStaleClaimGate {
            harness.disarmRemoveStaleClaimGate()
            firstResult = tracker.removeStaleClaim(ref, observed)
        }

        when: 'the outer reaper removes, the gate driving a competing removal first'
        def outerResult = tracker.removeStaleClaim(ref, observed)

        then: 'exactly one removal took effect and both calls returned coherently, the task ending Ready'
        firstResult instanceof RemoveStaleClaimResult.Removed
        outerResult instanceof RemoveStaleClaimResult.Mismatch
        (outerResult as RemoveStaleClaimResult.Mismatch).currentVersion() == null
        tracker.fetchTask(ref).state() == new TrackerTaskState.Ready()
    }

    def "an armed removal gate runs before removeStaleClaim competes for the store lock"() {
        given: 'a Working task holding a live claim and an armed removal gate'
        def ref = new TaskRef('fixture:remove-gate')
        harness.seedWorkingWithClaim(tracker, ref, 'instance-a')
        ClaimVersion observed = claimVersionOf(ref)
        boolean gateRan = false
        harness.armRemoveStaleClaimGate { gateRan = true }

        when: 'removeStaleClaim is called'
        tracker.removeStaleClaim(ref, observed)

        then: 'the armed gate ran'
        gateRan
    }

    def "disarming the removal gate restores normal removeStaleClaim behavior"() {
        given: 'a Working task with a removal gate armed then disarmed'
        def ref = new TaskRef('fixture:remove-gate-disarm')
        harness.seedWorkingWithClaim(tracker, ref, 'instance-a')
        ClaimVersion observed = claimVersionOf(ref)
        boolean gateRan = false
        harness.armRemoveStaleClaimGate { gateRan = true }
        harness.disarmRemoveStaleClaimGate()

        when: 'removeStaleClaim is called'
        tracker.removeStaleClaim(ref, observed)

        then: 'the disarmed gate never ran'
        !gateRan
    }

    def "listOpen fully releases the store lock on exit"() {
        given: 'a Working task holding a live claim'
        def ref = new TaskRef('fixture:list-open-unlock')
        harness.seedWorkingWithClaim(tracker, ref, 'instance-a')

        when: 'listOpen returns'
        tracker.listOpen()

        then: 'a different thread can immediately acquire the lock, proving it was released'
        lockIsFreeFromAnotherThread(tracker)
    }

    def "heartbeat fully releases the store lock on exit"() {
        given: 'a Working task holding a live claim'
        def ref = new TaskRef('fixture:heartbeat-unlock')
        harness.seedWorkingWithClaim(tracker, ref, 'instance-a')

        when: 'heartbeat returns'
        tracker.heartbeat(ref, 'stage=build')

        then: 'a different thread can immediately acquire the lock, proving it was released'
        lockIsFreeFromAnotherThread(tracker)
    }

    def "removeStaleClaim fully releases the store lock on exit"() {
        given: 'a Working task holding a live claim'
        def ref = new TaskRef('fixture:remove-unlock')
        harness.seedWorkingWithClaim(tracker, ref, 'instance-a')
        ClaimVersion observed = claimVersionOf(ref)

        when: 'removeStaleClaim returns'
        tracker.removeStaleClaim(ref, observed)

        then: 'a different thread can immediately acquire the lock, proving it was released'
        lockIsFreeFromAnotherThread(tracker)
    }

    private ClaimVersion claimVersionOf(TaskRef ref) {
        def entry = tracker.listOpen().find { it.ref() == ref }
        assert entry?.claimVersion() != null
        entry.claimVersion()
    }
}
