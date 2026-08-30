package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.StateLabels
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * StalenessMemory: the core staleness policy (design D2) under a controlled monotonic
 * clock — the fresh-observer grace period, the beaten-claim-never-stale property, the
 * exact TTL boundary, forgetting on disappearance, and once-per-shape emission — plus the window
 * grace the FR19 generalization added, and the eligibility filter it removed.
 *
 * FR2, NFR-R1 of add-claim-heartbeat; FR19 of harden-task-branch-contract.
 */
class StalenessMemorySpec extends Specification {

    private static final Duration TTL = Duration.ofMinutes(15)
    // An ancient server timestamp: staleness must NEVER read it (D2 forbids now - updatedAt).
    private static final Instant ANCIENT = Instant.parse('2000-01-01T00:00:00Z')

    private final VirtualMonotonicTime time = new VirtualMonotonicTime()
    private final StalenessMemory memory = new StalenessMemory(time, TTL)

    private static TrackerObservation working(String ref, ClaimVersion version) {
        TrackerObservation.of(new TaskRef(ref), workingFacts(version))
    }

    private static TrackerFacts workingFacts(ClaimVersion version) {
        def claim = version == null ? new ClaimFacts.None() : new ClaimFacts.Live('inst-1', version)
        TrackerFacts.of(StateLabels.workingOnly(), claim)
    }

    private static TrackerObservation awaitingHuman(String ref) {
        TrackerObservation.of(new TaskRef(ref), TrackerFacts.of(StateLabels.needsHumanOnly()))
    }

    private static TrackerObservation foreign(String ref) {
        TrackerObservation.of(
                new TaskRef(ref), TrackerFacts.of(new StateLabels(false, false, false, false, false)))
    }

    private static ClaimVersion version(String updatedAt = ANCIENT.toString()) {
        new ClaimVersion('marker-1', Instant.parse(updatedAt), new ClaimEpoch(1))
    }

    private static TrackerRepair stale(String ref, ClaimVersion version) {
        def facts = workingFacts(version)
        new TrackerRepair(new TaskRef(ref), facts, new TrackerShape.Claimed(facts.claim()))
    }

    private static TrackerRepair pending(String ref) {
        def facts = workingFacts(null)
        new TrackerRepair(new TaskRef(ref), facts, new TrackerShape.ClaimPending())
    }

    // FR2 grace period by construction: a fresh observer meeting a claim whose SERVER
    //     timestamp is already ancient does not treat it as stale before TTL elapses on
    //     its OWN clock from its first sighting.
    def "a freshly observed claim with an ancient server timestamp is not stale until TTL elapses locally"() {
        given:
        def v = version()

        expect: 'first sighting is never stale, however old the server timestamp'
        memory.observe([working('T-1', v)]) == []

        when: 'the observer waits just under TTL on its own clock'
        time.advance(TTL.minusNanos(1))

        then: 'still within the grace window'
        memory.observe([working('T-1', v)]) == []
    }

    // FR2, NFR-R1: exactly at TTL from first sighting the claim crosses to stale
    //     (kills the >= vs > boundary mutant deterministically).
    def "a claim becomes stale exactly at TTL from its first local sighting"() {
        given:
        def v = version()
        memory.observe([working('T-1', v)])

        when:
        time.advance(TTL)

        then: 'elapsed == TTL is stale'
        memory.observe([working('T-1', v)]) == [stale('T-1', v)]
    }

    // NFR-R1: one nanosecond before TTL is not yet stale (the low side of the boundary).
    def "a claim one nanosecond before TTL is not yet stale"() {
        given:
        def v = version()
        memory.observe([working('T-1', v)])

        when:
        time.advance(TTL.minusNanos(1))

        then:
        memory.observe([working('T-1', v)]) == []
    }

    // FR2 beaten claim never goes stale: the version changes within every TTL window, so
    //     the first-seen timer resets each beat and the claim is never classified stale
    //     across many TTLs.
    def "a claim beaten every interval never goes stale across many TTLs"() {
        given: 'a beat interval well under TTL (5m vs 15m), watched for 12 intervals (4 TTLs)'
        def interval = Duration.ofMinutes(5)
        def stales = []

        when:
        (1..12).each { beat ->
            // each beat refreshes updatedAt -> a new version the observer reads
            def beaten = new ClaimVersion('marker-1', ANCIENT.plusSeconds(beat), new ClaimEpoch(1))
            time.advance(interval)
            stales += memory.observe([working('T-1', beaten)])
        }

        then: 'never stale in any window'
        stales == []
    }

    // FR2, NFR-R1: a beat that lands one nanosecond before TTL resets the timer, so the
    //     claim gets a fresh full TTL from the new version's first sighting.
    def "a version change resets the staleness timer"() {
        given:
        def v1 = version()
        memory.observe([working('T-1', v1)])

        and: 'time advances to just before the first version would go stale'
        time.advance(TTL.minusNanos(1))

        when: 'a beat lands: a new version is observed'
        def v2 = new ClaimVersion('marker-1', ANCIENT.plusSeconds(1), new ClaimEpoch(1))
        def afterBeat = memory.observe([working('T-1', v2)])

        and: 'then almost a full new TTL passes but not quite'
        time.advance(TTL.minusNanos(1))
        def beforeSecond = memory.observe([working('T-1', v2)])

        and: 'then the new version crosses its own TTL'
        time.advance(Duration.ofNanos(1))
        def atSecond = memory.observe([working('T-1', v2)])

        then: 'the beat cleared staleness and the timer restarted from the new sighting'
        afterBeat == []
        beforeSecond == []
        atSecond == [stale('T-1', v2)]
    }

    // FR2, FR4: a stale claim is emitted exactly ONCE for a given version, not on every
    //     subsequent tick, so the reaper is not driven to redundant removals.
    def "a stale claim is emitted only once per version"() {
        given:
        def v = version()
        memory.observe([working('T-1', v)])
        time.advance(TTL)

        expect: 'the tick that crosses the threshold emits it'
        memory.observe([working('T-1', v)]) == [stale('T-1', v)]

        when: 'a later tick still sees the same stale version'
        time.advance(Duration.ofMinutes(5))

        then: 'it is not re-emitted'
        memory.observe([working('T-1', v)]) == []
    }

    // FR2, NFR-R1 no leak: a claim absent from listOpen is forgotten, and if it reappears
    //     its timer restarts from the later first sighting (not from the original one).
    def "a claim that disappears from listOpen is forgotten and its timer restarts on return"() {
        given:
        def v = version()
        memory.observe([working('T-1', v)])

        when: 'the claim goes stale-old but vanishes from listOpen before being observed stale'
        time.advance(TTL)

        then: 'an empty listing forgets it (no emission, memory cleared)'
        memory.observe([]) == []

        and: 'on return it is fresh again - not immediately stale despite the elapsed time'
        memory.observe([working('T-1', v)]) == []

        when: 'just under a fresh TTL passes since the return'
        time.advance(TTL.minusNanos(1))

        then: 'still not stale - the timer restarted from the return, not the original sighting'
        memory.observe([working('T-1', v)]) == []

        when: 'the fresh TTL completes'
        time.advance(Duration.ofNanos(1))

        then:
        memory.observe([working('T-1', v)]) == [stale('T-1', v)]
    }

    // FR19 of harden-task-branch-contract: the eligibility filter is gone. A parked task is a
    //     steady shape and is never released; a working task with no claim footprint is the claim
    //     sequence's own frozen window, released for repair once its grace has stood.
    def "a parked task is never released while a claimless working task is, after its grace"() {
        given: 'a parked task and a working task whose claim marker never landed'
        def entries = [
            awaitingHuman('T-await'),
            working('T-noclaim', null)
        ]

        expect: 'neither is released on first sighting'
        memory.observe(entries) == []

        when: 'the grace window stands'
        time.advance(TTL)

        then: 'only the frozen claim window is released, and the parked task never is'
        memory.observe(entries) == [pending('T-noclaim')]

        when: 'far past any window'
        time.advance(TTL.multipliedBy(10))

        then: 'the released shape is not re-emitted and the parked task is still untouched'
        memory.observe(entries) == []
    }

    // FR19: a Foreign shape is not steady, but no owner repairs it either, so the memory does not
    //     time it: latching it would enter it into staleRefs — which the liveness oracle reads as
    //     "unowned" — for a task no repair will ever converge.
    def "a foreign shape is never timed, however long it stands"() {
        given: 'an open task wearing no gnomish state label at all'
        def entries = [foreign('T-alien')]

        expect:
        memory.observe(entries) == []

        when:
        time.advance(TTL.multipliedBy(10))

        then: 'nothing is released and nothing is latched'
        memory.observe(entries) == []
        memory.staleRefs().isEmpty()
    }

    // FR2, FR4: eligible claims are judged even when mixed with ineligible entries, and
    //     multiple stale claims come back in listOpen order.
    def "multiple stale Working claims are returned in sweep order, steady entries ignored"() {
        given:
        def v1 = version()
        def v2 = version()
        def listing = [
            working('T-1', v1),
            awaitingHuman('T-await'),
            working('T-2', v2)
        ]
        memory.observe(listing)

        when:
        time.advance(TTL)

        then: 'both Working claims stale, in order; the parked entry absent'
        memory.observe(listing) == [
            stale('T-1', v1),
            stale('T-2', v2)
        ]
    }

    // FR9, D2: forgetAll discards every observation window, so the next observe treats
    //     every claim as first-seen again and its TTL restarts — even a claim already aged
    //     a full TTL (which would be stale without the forget) gets a fresh window.
    def "forgetAll discards all windows so an aged claim gets a fresh TTL on the next observe"() {
        given: 'a claim observed and aged a full TTL — without forget it would be stale now'
        def v = version()
        memory.observe([working('T-1', v)])
        time.advance(TTL)

        when: 'forgetAll discards the window before the claim is observed stale'
        memory.forgetAll()

        then: 'the same version is first-seen again, not stale despite the elapsed TTL'
        memory.observe([working('T-1', v)]) == []

        when: 'only just under a fresh TTL passes since the restart'
        time.advance(TTL.minusNanos(1))

        then: 'still within the fresh window'
        memory.observe([working('T-1', v)]) == []

        when: 'the fresh TTL completes'
        time.advance(Duration.ofNanos(1))

        then: 'now stale from the post-forget first sighting'
        memory.observe([working('T-1', v)]) == [stale('T-1', v)]
    }

    // FR9, D2: forgetAll clears every remembered claim, not just one — after it the whole
    //     listing is first-seen again.
    def "forgetAll forgets multiple claims at once"() {
        given: 'two claims observed and aged a full TTL'
        def v1 = version()
        def v2 = version()
        def listing = [
            working('T-1', v1),
            working('T-2', v2)
        ]
        memory.observe(listing)
        time.advance(TTL)

        when: 'forgetAll discards both windows'
        memory.forgetAll()

        then: 'neither is stale — both restart from the next observation'
        memory.observe(listing) == []
    }

    // FR2: a non-positive TTL is a configuration error - every claim would be stale on
    //     first sight, so the memory refuses to construct.
    def "construction rejects a non-positive TTL"() {
        when:
        new StalenessMemory(time, ttl)

        then:
        thrown(IllegalArgumentException)

        where:
        ttl << [
            Duration.ZERO,
            Duration.ofMinutes(-1)
        ]
    }

    // FR4, D14: retryEmission re-arms the once-per-version latch so a still-stale claim whose
    //     removal failed is emitted again on the next observe — the reaper's retry path after an
    //     infrastructure failure. Without it the same version would be observed but never re-emitted.
    def "retryEmission re-arms a still-stale claim so its unchanged version is emitted again"() {
        given:
        def v = version()
        memory.observe([working('T-1', v)])
        time.advance(TTL)

        expect: 'the crossing tick emits it once'
        memory.observe([working('T-1', v)]) == [stale('T-1', v)]

        and: 'a normal next observe does NOT re-emit the same version'
        memory.observe([working('T-1', v)]) == []

        when: 'retryEmission re-arms the latch, then the same version is observed again'
        memory.retryEmission(stale('T-1', v))

        then: 'the unchanged version is emitted afresh so the reaper can retry the removal'
        memory.observe([working('T-1', v)]) == [stale('T-1', v)]
    }

    // FR3 of add-serve-sandbox-lifecycle: staleRefs reflects the current latch state, not just
    //     this call's newly-emitted claims — a claim already latched stale on an earlier tick
    //     stays in staleRefs until its version changes or it disappears from listOpen.
    def "staleRefs reflects every currently-latched-stale claim, not only the newest emission"() {
        given:
        def v1 = version()
        memory.observe([working('T-1', v1)])

        expect: 'nothing latched yet'
        memory.staleRefs() == [] as Set

        when: 'T-1 crosses TTL and is emitted'
        time.advance(TTL)
        memory.observe([working('T-1', v1)])

        then:
        memory.staleRefs() == [new TaskRef('T-1')] as Set

        when: 'a later tick still sees the same stale version (no re-emission) plus a fresh T-2'
        def v2 = version()
        time.advance(Duration.ofMinutes(1))
        memory.observe([
            working('T-1', v1),
            working('T-2', v2)
        ])

        then: 'T-1 stays in staleRefs even though it was not re-emitted this tick; T-2 is not stale yet'
        memory.staleRefs() == [new TaskRef('T-1')] as Set
    }

    // FR3 of add-serve-sandbox-lifecycle: a version change (a beat) clears the latch — staleRefs
    //     no longer reports the ref once it has been superseded.
    def "staleRefs drops a ref once its stale version is superseded by a fresh beat"() {
        given:
        def v1 = version()
        memory.observe([working('T-1', v1)])
        time.advance(TTL)
        memory.observe([working('T-1', v1)])

        expect:
        memory.staleRefs() == [new TaskRef('T-1')] as Set

        when: 'a fresh beat lands with a new version'
        def v2 = new ClaimVersion('marker-1', ANCIENT.plusSeconds(1), new ClaimEpoch(1))
        memory.observe([working('T-1', v2)])

        then:
        memory.staleRefs() == [] as Set
    }

    // FR4, D14: retryEmission is guarded by the observed version — it re-arms only when the
    //     current observation is still the version that failed to remove. A stale-but-superseded
    //     version (a live beat moved the timer on) can never resurrect the current one.
    def "retryEmission for a superseded version does not re-arm the current one"() {
        given: 'v1 goes stale and is emitted'
        def v1 = version()
        memory.observe([working('T-1', v1)])
        time.advance(TTL)
        memory.observe([working('T-1', v1)])

        and: 'a beat brings v2, which also goes stale and is emitted'
        def v2 = new ClaimVersion('marker-1', ANCIENT.plusSeconds(1), new ClaimEpoch(1))
        memory.observe([working('T-1', v2)])
        time.advance(TTL)
        def v2Emitted = memory.observe([working('T-1', v2)])

        when: 'retryEmission is called for the OLD, superseded version v1'
        memory.retryEmission(stale('T-1', v1))

        then: 'v2 is not re-armed: a non-current version cannot force a re-emission'
        v2Emitted == [stale('T-1', v2)]
        memory.observe([working('T-1', v2)]) == []
    }
}
