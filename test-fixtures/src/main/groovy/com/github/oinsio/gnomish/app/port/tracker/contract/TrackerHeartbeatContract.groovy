package com.github.oinsio.gnomish.app.port.tracker.contract

import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker

/**
 * Heartbeat-write properties of the {@link Tracker} port contract suite
 * (tracker-port spec, "Heartbeat write updates the claim marker in place" and
 * the "Version changes are observable across instances" scenario; task 2.2,
 * FR1, FR5, FR8 of add-claim-heartbeat). Extends {@link TrackerLeaseContract}
 * to reuse its {@code arrange}/{@code seedTask}/{@code seedWorkingWithClaim}
 * seams rather than duplicating them; a concrete adapter subclass instantiates
 * the MOST-DERIVED class in this chain (not {@link TrackerLeaseContract} or any
 * earlier link directly) to run the full suite, per M1 — the same suite against
 * every adapter. Kept in a fifth file because the combined property set of the
 * whole chain would exceed the project's per-file line cap.
 *
 * <p>No concrete adapter spec extends this class yet: the in-memory reference is
 * wired in task 2.4 and the GitHub adapter in task 3.5, once their adapters
 * implement {@code heartbeat}/{@code removeStaleClaim} and the {@code
 * seedWorkingWithClaim} seam. Until then this abstract spec is never
 * instantiated and therefore does not run — intentional, and it keeps the build
 * green for both adapters between tasks.
 *
 * <p>The "claim gone" property makes the claim disappear by calling the port's
 * own {@code removeStaleClaim} — the realistic path (a reaper deleted the
 * marker) — rather than introducing a new deletion seam. That keeps the fixture
 * setup at the port level and needs no adapter-specific hook. Task 2.3 adds a
 * further {@code TrackerReapContract extends TrackerHeartbeatContract} covering
 * {@code removeStaleClaim} in its own right.
 *
 * <p>Implements FR1, FR5, FR8 of add-claim-heartbeat.
 */
abstract class TrackerHeartbeatContract extends TrackerLeaseContract {

    // FR1, FR5: a beat refreshes the claim version in place — a later observer
    //     reads a different version while the marker identity stays stable, and
    //     the version the Beaten result reports is exactly the one listOpen then
    //     shows ("Beat refreshes the version" / "Version changes are observable
    //     across instances")
    def "heartbeat refreshes the version in place, keeping marker identity, observably to another instance"() {
        given: 'a Working task holding a live claim, its pre-beat version read via listOpen'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'heartbeat version-refresh fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:heartbeat-refresh')
        seedWorkingWithClaim(adapter, ref, 'instance-a')
        ClaimVersion before = versionOf(adapter, ref)

        when: 'the holder beats its claim with a progress payload'
        def result = adapter.heartbeat(ref, 'stage=build attempt=1 alive-at=now')

        then: 'the beat succeeds'
        result instanceof HeartbeatResult.Beaten

        and: 'a second observer reads a different version after the beat — the change is observable'
        ClaimVersion after = versionOf(adapter, ref)
        after != before

        and: 'the marker identity is stable across the beat — only the version as a whole moved on'
        after.markerId() == before.markerId()

        and: 'the version the beat reports is exactly the one listOpen now shows to any instance'
        (result as HeartbeatResult.Beaten).version() == after
    }

    // FR8: a beat after the claim marker is gone yields the ClaimGone RESULT, a
    //     protocol signal distinguishable from an infrastructure failure — the
    //     port reports it, never throws ("Gone claim is a signal, not an error")
    def "heartbeat after the claim marker is removed reports ClaimGone rather than throwing"() {
        given: 'a Working task holding a live claim that a reaper then removes via removeStaleClaim'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'heartbeat claim-gone fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:heartbeat-gone')
        seedWorkingWithClaim(adapter, ref, 'instance-a')
        adapter.removeStaleClaim(ref, claimFactsOf(adapter, ref))

        when: 'the former holder beats a claim that no longer exists'
        def result = adapter.heartbeat(ref, 'stage=build attempt=1 alive-at=now')

        then: 'the loss is a distinct result, not a thrown exception — distinguishable from an outage'
        result instanceof HeartbeatResult.ClaimGone
    }

    // FR8: a beat against a task the tracker no longer holds at all is the STRONGEST
    //     form of "claim gone" — the ClaimGone RESULT, never a thrown exception. This
    //     pins the reference and live adapters to the SAME observable behavior for a
    //     vanished task: in-memory returns ClaimGone instead of NoSuchTrackedTaskException,
    //     GitHub maps the issue's 404 listing to ClaimGone rather than an infra failure —
    //     so InstanceHeartbeat flags the lost claim rather than WARN-and-retrying forever
    def "heartbeat against a task the tracker does not know reports ClaimGone, not an exception"() {
        given: 'a tracker with NO fixture task seeded for this ref'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'heartbeat unknown-task fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:heartbeat-unknown')

        when: 'the beat targets a task the tracker has never heard of'
        def result = adapter.heartbeat(ref, 'stage=build attempt=1 alive-at=now')

        then: 'the claim is reported gone, distinguishable from an infrastructure failure — never thrown'
        result instanceof HeartbeatResult.ClaimGone
    }

    /**
     * Reads the current live {@link ClaimVersion} of {@code ref} from {@code
     * listOpen} — the same fact any other instance observes — so the properties
     * above never construct a version literal but always read one back through
     * the port, per {@link ClaimVersion}'s opaque contract.
     */
    private static ClaimVersion versionOf(Tracker adapter, TaskRef ref) {
        OpenTask entry = adapter.listOpen().find { it.ref() == ref }
        assert entry?.claimVersion() != null
        entry.claimVersion()
    }

    /**
     * Reads the current {@link ClaimFacts} footprint of {@code ref} from {@code listOpen} — the
     * observation a reaper acts on, and the guard {@code removeStaleClaim} re-checks (FR19 of
     * harden-task-branch-contract). Protected so the reap and returned-fact links reuse the one
     * reader rather than each constructing a footprint literal.
     */
    protected static ClaimFacts claimFactsOf(Tracker adapter, TaskRef ref) {
        OpenTask entry = adapter.listOpen().find { it.ref() == ref }
        assert entry != null
        entry.facts().claim()
    }
}
