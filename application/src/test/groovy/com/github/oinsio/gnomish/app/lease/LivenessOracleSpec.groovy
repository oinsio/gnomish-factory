package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * LivenessOracle: the tracked-object liveness verdict (proposal FR3, design D1) computed
 * forward from the reaper's own listing — no second tracker call — filtered against the SAME
 * {@link StalenessMemory} the claim reaper drives. Drives the oracle end to end through a real
 * {@link Reaper} tick so the sink wiring and the staleness latch are exercised together, exactly
 * as they will be shared on a live serve daemon (task 2.1).
 *
 * FR3, NFR-C2, NFR-R1 of add-serve-sandbox-lifecycle.
 */
class LivenessOracleSpec extends Specification {

    private static final Duration TTL = Duration.ofMinutes(15)
    private static final Instant ANCIENT = Instant.parse('2000-01-01T00:00:00Z')

    private final Tracker tracker = Mock()
    private final VirtualMonotonicTime time = new VirtualMonotonicTime()
    private final StalenessMemory memory = new StalenessMemory(time, TTL)
    private final CachedOpenTaskListing cache = new CachedOpenTaskListing()
    private final Reaper reaper = new Reaper(tracker, memory, cache)
    private final LivenessOracle oracle = new LivenessOracle(cache, memory)

    private static OpenTask working(String ref, ClaimVersion version = version()) {
        new OpenTask(new TaskRef(ref), new TrackerTaskState.Working('inst-1'), version, 'fixture title')
    }

    private static OpenTask awaitingHuman(String ref) {
        new OpenTask(new TaskRef(ref), new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION), null, 'fixture title')
    }

    private static ClaimVersion version(String marker = 'm1') {
        new ClaimVersion(marker, ANCIENT)
    }

    // Task 2.1: the live key set is computed FORWARD via TaskIdSanitizer, recomputed every call
    //     — no reverse key-to-task mapping. Weird-but-valid task ids sanitize the same way the
    //     Docker labels already do.
    def "evaluate computes the live key set forward via the sanitizer from the shared listing"() {
        given:
        tracker.listOpen() >> [
            working('feature/ADD-123: fix it'),
            awaitingHuman('T-await')
        ]
        reaper.reapOnce([])

        when:
        reaper.reapOnce([])
        def verdict = oracle.evaluate()

        then:
        verdict instanceof LivenessVerdict.Live
        ((LivenessVerdict.Live) verdict).environmentKeys() ==
                [
                    'feature-ADD-123-fix-it',
                    'T-await'
                ] as Set
    }

    // Task 2.1: re-evaluating after the listing changes recomputes the set — no caching beyond
    //     "most recent tick".
    def "evaluate recomputes on every call as the underlying listing changes"() {
        when:
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> [working('T-1')]

        expect:
        ((LivenessVerdict.Live) oracle.evaluate()).environmentKeys() == ['T-1'] as Set

        when:
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> [
            working('T-1'),
            working('T-2', version('m2'))
        ]

        and:
        ((LivenessVerdict.Live) oracle.evaluate()).environmentKeys() == ['T-1', 'T-2'] as Set
    }

    // Task 2.2, NFR-R1: a fresh oracle before any tick — or one whose only tick was an outage —
    //     yields NoVerdict, categorically distinct from a real empty listing.
    def "yields NoVerdict before any successful tick, distinct from an empty listing"() {
        expect: 'no tick has ever run'
        oracle.evaluate() instanceof LivenessVerdict.NoVerdict

        when: 'a real tick observes zero open tasks'
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> []

        and: 'an empty listing is a real verdict, not NoVerdict'
        def verdict = oracle.evaluate()
        verdict instanceof LivenessVerdict.Live
        ((LivenessVerdict.Live) verdict).environmentKeys() == [] as Set
    }

    // Task 2.2, design D4 "geometry row 1": tracker unreachable from the sweeper itself — a
    //     prior successful listing is superseded by NoVerdict, not left stale-and-trusted.
    def "a listOpen outage yields NoVerdict even after a prior successful listing"() {
        when: 'a successful tick establishes a live listing'
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> [working('T-1')]
        oracle.evaluate() instanceof LivenessVerdict.Live

        when: 'the next tick listOpen fails'
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> { throw new RuntimeException('tracker down') }

        and: 'the oracle reports NoVerdict, not the stale prior listing'
        oracle.evaluate() instanceof LivenessVerdict.NoVerdict
    }

    // Task 2.2, design D4 "geometry row 2": beats fail but a sibling instance's sweep still
    //     reads the tracker — this instance's OWN oracle still sees the listing (it is the
    //     sibling scenario from the sibling's point of view; here we assert the oracle acts on
    //     whatever tracker.listOpen() actually returns, independent of this instance's beat
    //     health) — objects are stopped after TTL exactly as the takeover contract already
    //     promises, once the claim goes stale.
    def "objects are classified unowned after TTL exactly as the takeover contract already licenses"() {
        given:
        def v = version()

        when:
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> [working('T-1', v)]
        ((LivenessVerdict.Live) oracle.evaluate()).environmentKeys() == ['T-1'] as Set

        when: 'TTL elapses with the version unchanged — the claim goes stale and is reaped'
        time.advance(TTL)
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> [working('T-1', v)]
        1 * tracker.removeStaleClaim(new TaskRef('T-1'), v) >> new RemoveStaleClaimResult.Removed()

        and: 'the SAME tick, the oracle already classifies it unowned — takeover and unowned agree'
        ((LivenessVerdict.Live) oracle.evaluate()).environmentKeys() == [] as Set
    }

    // Task 2.3, FR3: a stale-claim task's objects classify unowned exactly when the takeover
    //     license applies — asserted directly against the reaper's own emitted StaleClaim.
    def "a task classifies unowned exactly when its claim is emitted stale by the same memory the reaper acts on"() {
        given:
        def v = version()
        tracker.listOpen() >> [working('T-1', v)]

        when:
        reaper.reapOnce([])

        then: 'not yet stale: still live'
        ((LivenessVerdict.Live) oracle.evaluate()).environmentKeys() == ['T-1'] as Set

        when: 'one nanosecond before TTL'
        time.advance(TTL.minusNanos(1))
        reaper.reapOnce([])

        then: 'still live — takeover is not yet licensed'
        0 * tracker.removeStaleClaim(_, _)
        ((LivenessVerdict.Live) oracle.evaluate()).environmentKeys() == ['T-1'] as Set

        when: 'TTL completes — takeover is now licensed'
        time.advance(Duration.ofNanos(1))
        reaper.reapOnce([])

        then:
        1 * tracker.removeStaleClaim(new TaskRef('T-1'), v) >> new RemoveStaleClaimResult.Removed()
        ((LivenessVerdict.Live) oracle.evaluate()).environmentKeys() == [] as Set
    }

    // Task 2.3: the instance's OWN currently-held claim is never excluded from the live set —
    //     only foreign staleness observation excludes it (design D13), so a self-held task's
    //     environment is always live from its own oracle's point of view.
    def "the instance's own held claim stays live even though it is excluded from staleness observation"() {
        given:
        def ownRef = new TaskRef('T-own')

        when:
        reaper.reapOnce([ownRef])

        then:
        1 * tracker.listOpen() >> [working('T-own', version())]

        and:
        ((LivenessVerdict.Live) oracle.evaluate()).environmentKeys() == ['T-own'] as Set

        when: 'far past TTL, still held by this instance'
        time.advance(TTL.multipliedBy(10))
        reaper.reapOnce([ownRef])

        then:
        1 * tracker.listOpen() >> [working('T-own', version())]
        0 * tracker.removeStaleClaim(_, _)

        and: 'never excluded from the live set — own claims are never observed for staleness'
        ((LivenessVerdict.Live) oracle.evaluate()).environmentKeys() == ['T-own'] as Set
    }
}
