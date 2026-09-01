package com.github.oinsio.gnomish.app.lease

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * Reaper: the policy behind the real {@link ReaperDuty}. Each reapOnce lists open
 * tasks, feeds the {@link StalenessMemory}, and removes every claim the memory just
 * judged stale — never claiming a reaped task for itself (removal alone returns it
 * to Ready). Convergence is by the version guard, and a listOpen outage forgets the
 * observation windows so recovery restarts each claim's TTL — no false staleness
 * accrues and no live claim is reaped (FR9). These specs drive reapOnce directly
 * under a controlled monotonic clock with a mocked Tracker, so the policy is
 * exercised with no threading and no real time. `Reaper`/`StalenessMemory` behavior
 * itself is unchanged by fix-reaper-idle-liveness — only who calls reapOnce and how
 * often moved, from the beat tick to a standing thread (see {@link StandingReaper}).
 *
 * FR4, FR9, NFR-R2 of add-claim-heartbeat.
 */
class ReaperSpec extends Specification {

    private static final Duration TTL = Duration.ofMinutes(15)
    private static final Instant ANCIENT = Instant.parse('2000-01-01T00:00:00Z')

    private final Tracker tracker = Mock(Tracker) { listReady(_) >> [] }
    private final VirtualMonotonicTime time = new VirtualMonotonicTime()
    private final StalenessMemory memory = new StalenessMemory(time, TTL)
    private final Reaper reaper = new Reaper(tracker, memory)

    private static OpenTask working(String ref, ClaimVersion version) {
        new OpenTask(new TaskRef(ref), new TrackerTaskState.Working('inst-1'), version, 'fixture title')
    }

    private static ClaimFacts claimOf(ClaimVersion version, String holder = 'inst-1') {
        new ClaimFacts.Live(holder, version)
    }

    private static ClaimVersion version(String marker = 'm1', String updatedAt = ANCIENT.toString()) {
        new ClaimVersion(marker, Instant.parse(updatedAt), new ClaimEpoch(1))
    }

    /**
     * Migrated to the shared helper (`.claude/rules/logging.md`) when task 5.4 touched this spec —
     * pinned at DEBUG, which is where the converging no-op now lives (FR12).
     */
    private static List<ILoggingEvent> capture(Closure<Void> emit) {
        def logs = LogCaptureSupport.attach(Reaper, Level.DEBUG)
        try {
            emit()
            return List.copyOf(logs.list)
        } finally {
            logs.detach()
        }
    }

    // FR4: a claim whose version stood unchanged for TTL is removed with the observed
    //     version; a claim first seen this tick is not stale and is left alone.
    def "reaps a claim gone stale with its observed version, leaving a fresh claim untouched"() {
        given:
        def v = version()

        when: 'first tick records the claim as first-seen'
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> [working('T-stale', v)]
        0 * tracker.removeStaleClaim(_, _)

        when: 'TTL elapses with the version unchanged; a brand-new claim also appears'
        time.advance(TTL)
        reaper.reapOnce([])

        then: 'only the stale one is removed, with the exact observed version'
        1 * tracker.listOpen() >> [
            working('T-stale', v),
            working('T-fresh', version('m2'))
        ]
        1 * tracker.removeStaleClaim(new TaskRef('T-stale'), claimOf(v)) >> new RemoveStaleClaimResult.Removed()
        0 * tracker.removeStaleClaim(new TaskRef('T-fresh'), _)
    }

    // FR4, D4: the reaper NEVER claims a reaped task for itself — removal alone returns
    //     it to the ordinary queue.
    def "the reaper never claims a reaped task for itself"() {
        given:
        def v = version()

        when:
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> [working('T-1', v)]

        when:
        time.advance(TTL)
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> [working('T-1', v)]
        1 * tracker.removeStaleClaim(new TaskRef('T-1'), claimOf(v)) >> new RemoveStaleClaimResult.Removed()
        0 * tracker.claim(_, _)
    }

    // FR4, NFR-R2: a Mismatch (a racing reaper or a live beat changed the claim since the
    //     observation) is a safe no-op, never an error — the reaper converges and reaps on.
    def "a Mismatch on one stale claim is not an error and the other is still reaped"() {
        given:
        def v1 = version('m1')
        def v2 = version('m2')
        def open = [
            working('T-1', v1),
            working('T-2', v2)
        ]

        when:
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> open

        when:
        time.advance(TTL)
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> open
        1 * tracker.removeStaleClaim(new TaskRef('T-1'), claimOf(v1)) >> new RemoveStaleClaimResult.Mismatch(null)
        1 * tracker.removeStaleClaim(new TaskRef('T-2'), claimOf(v2)) >> new RemoveStaleClaimResult.Removed()
        noExceptionThrown()
    }

    // FR4, NFR-R2: only the Mismatch outcome logs the "already changed; converging" no-op line — a
    //     Removed reap does not. Distinguishes the exact branch a Removed-vs-Mismatch swap would take.
    def "a Mismatch logs the converging no-op line for that claim, a Removed does not"() {
        given:
        def v1 = version('m1')
        def v2 = version('m2')
        def open = [
            working('T-1', v1),
            working('T-2', v2)
        ]

        when: 'both are first seen'
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> open

        when: 'TTL elapses and both are reaped — T-1 mismatches, T-2 is removed'
        time.advance(TTL)
        def events = capture {
            reaper.reapOnce([])
        }

        then:
        1 * tracker.listOpen() >> open
        1 * tracker.removeStaleClaim(new TaskRef('T-1'), claimOf(v1)) >> new RemoveStaleClaimResult.Mismatch(null)
        1 * tracker.removeStaleClaim(new TaskRef('T-2'), claimOf(v2)) >> new RemoveStaleClaimResult.Removed()

        // FR12 of harden-logging-observability: DEBUG, not INFO — under contention a claim another
        //     instance already changed is the design converging, not a state change to report.
        and: 'exactly the mismatched claim logs the converging line; the removed one does not'
        def converging = events.findAll {
            it.level == Level.DEBUG && it.formattedMessage.contains('converging')
        }
        converging.size() == 1
        converging[0].formattedMessage.contains('T-1')
        !converging[0].formattedMessage.contains('T-2')
    }

    // FR4: an infrastructure failure removing ONE stale claim must not stop reaping the rest.
    def "an infrastructure failure removing one stale claim does not stop the others"() {
        given:
        def v1 = version('m1')
        def v2 = version('m2')
        def open = [
            working('T-1', v1),
            working('T-2', v2)
        ]

        when:
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> open

        when:
        time.advance(TTL)
        reaper.reapOnce([])

        then:
        1 * tracker.removeStaleClaim(new TaskRef('T-1'), claimOf(v1)) >> {
            throw new RuntimeException('5xx')
        }
        1 * tracker.removeStaleClaim(new TaskRef('T-2'), claimOf(v2)) >> new RemoveStaleClaimResult.Removed()
        1 * tracker.listOpen() >> open
        noExceptionThrown()
    }

    // FR9, D2: a listOpen outage forgets the observation windows and never propagates;
    //     recovery restarts each claim's TTL from its first post-outage sighting, so a
    //     genuinely dead claim (version never changed across the outage) is reaped only
    //     after a FULL fresh TTL following recovery — not instantly on recovery.
    //     (Updated from task 4.3's prompt-recovery semantics: forget-on-outage trades one
    //     fresh TTL of promptness for the FR9 guarantee that no live claim is falsely reaped.)
    def "a listOpen outage forgets windows and a dead claim is reaped only a fresh TTL after recovery"() {
        given:
        def v = version()

        when: 'a first successful observation records first-seen'
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> [working('T-1', v)]
        0 * tracker.removeStaleClaim(_, _)

        when: 'the tracker is down for a tick spanning a full TTL'
        time.advance(TTL)
        reaper.reapOnce([])

        then: 'the outage tick lists nothing, reaps nothing, and does not propagate'
        1 * tracker.listOpen() >> { throw new RuntimeException('tracker down') }
        0 * tracker.removeStaleClaim(_, _)
        noExceptionThrown()

        when: 'the tracker recovers with the unchanged (dead) version'
        reaper.reapOnce([])

        then: 'recovery restarts the window: NOT reaped on the first recovery tick'
        1 * tracker.listOpen() >> [working('T-1', v)]
        0 * tracker.removeStaleClaim(_, _)

        when: 'a full fresh TTL passes with the version still unchanged'
        time.advance(TTL)
        reaper.reapOnce([])

        then: 'only now is the genuinely dead claim reaped, with the observed version'
        1 * tracker.listOpen() >> [working('T-1', v)]
        1 * tracker.removeStaleClaim(new TaskRef('T-1'), claimOf(v)) >> new RemoveStaleClaimResult.Removed()
    }

    // FR9 "Long outage, no casualties": the tracker is down for SEVERAL TTLs while a live
    //     holder works; on recovery the first listOpen reads the SAME pre-outage version
    //     (the holder also lost access and has not yet re-beaten). Because every outage tick
    //     forgot the windows, the claim is first-seen again and is NOT reaped; the holder
    //     then resumes beating and stays live across further ticks. No live claim is reaped.
    def "a live holder survives a multi-TTL outage even if its first recovery version is unchanged"() {
        given:
        def before = version('m1', '2000-01-01T00:00:00Z')

        when: 'first observation of the live claim'
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> [working('T-1', before)]
        0 * tracker.removeStaleClaim(_, _)

        when: 'the tracker is down for SEVERAL TTLs — each outage tick forgets the windows'
        (1..4).each {
            time.advance(TTL)
            reaper.reapOnce([])
        }

        then: 'no observation, so no staleness accrues and nothing is reaped during the outage'
        4 * tracker.listOpen() >> { throw new RuntimeException('tracker down') }
        0 * tracker.removeStaleClaim(_, _)

        when: 'recovery reads the SAME pre-outage version — holder has not re-beaten yet'
        reaper.reapOnce([])

        then: 'fresh window from this first sighting: the live claim is NOT reaped'
        1 * tracker.listOpen() >> [working('T-1', before)]
        0 * tracker.removeStaleClaim(_, _)

        when: 'the holder resumes beating within the fresh TTL and keeps beating'
        (1..3).each { beat ->
            def beaten = version('m1', "2000-01-01T0${beat}:00:00Z")
            time.advance(Duration.ofMinutes(5))
            reaper.reapOnce([])
        }

        then: 'the beats keep the version changing inside every TTL window: never reaped'
        3 * tracker.listOpen() >>> [
            [
                working('T-1', version('m1', '2000-01-01T01:00:00Z'))
            ],
            [
                working('T-1', version('m1', '2000-01-01T02:00:00Z'))
            ],
            [
                working('T-1', version('m1', '2000-01-01T03:00:00Z'))
            ]
        ]
        0 * tracker.removeStaleClaim(_, _)
    }

    // FR4, D13, G2: the reaper NEVER removes a claim the instance itself holds — even when that
    //     claim's version has stood unchanged past the TTL (its beats were failing while listOpen
    //     kept working). Own claims are excluded before observation, so only a foreign observer
    //     could reap this instance; a foreign stale claim in the same listing is still reaped.
    def "the reaper never removes a claim held by its own instance"() {
        given:
        def ownRef = new TaskRef('T-own')
        def ownVersion = version('own')
        def foreignVersion = version('foreign')
        def open = [
            working('T-own', ownVersion),
            working('T-foreign', foreignVersion)
        ]

        when: 'first tick: the own claim is excluded, only the foreign one is observed'
        reaper.reapOnce([ownRef])

        then:
        1 * tracker.listOpen() >> open
        0 * tracker.removeStaleClaim(_, _)

        when: 'both versions stand unchanged well past the TTL'
        time.advance(TTL)
        reaper.reapOnce([ownRef])

        then: 'only the foreign claim is reaped; the own claim is never removed'
        1 * tracker.listOpen() >> open
        1 * tracker.removeStaleClaim(new TaskRef('T-foreign'), claimOf(foreignVersion)) >> new RemoveStaleClaimResult.Removed()
        0 * tracker.removeStaleClaim(ownRef, _)
    }

    // FR4, D14: an infrastructure failure removing a stale claim must NOT silence it — the
    //     once-per-version latch is re-armed so the SAME unchanged version is retried on the
    //     next tick, rather than staying Working and un-emitted until the version changes.
    def "an infrastructure failure removing a stale claim re-arms it for retry next tick"() {
        given:
        def v = version()
        def open = [working('T-1', v)]

        when: 'first sighting records first-seen'
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> open
        0 * tracker.removeStaleClaim(_, _)

        when: 'TTL elapses and the removal fails with an infrastructure error'
        time.advance(TTL)
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> open
        1 * tracker.removeStaleClaim(new TaskRef('T-1'), claimOf(v)) >> {
            throw new RuntimeException('5xx')
        }

        when: 'the next tick sees the same unchanged version'
        reaper.reapOnce([])

        then: 'the reaper retries the removal instead of staying silent'
        1 * tracker.listOpen() >> open
        1 * tracker.removeStaleClaim(new TaskRef('T-1'), claimOf(v)) >> new RemoveStaleClaimResult.Removed()
    }

    // FR3, NFR-C2 of add-serve-sandbox-lifecycle: a successful listOpen is published to the
    //     sink verbatim, INCLUDING the instance's own claims — the exclusion below is only for
    //     staleness observation, not for what the sink sees (the liveness oracle needs the own
    //     task's key in the live set too).
    def "publishes a successful listing to the sink, own claims included"() {
        given:
        def sink = Mock(OpenTaskListingSink)
        def reaperWithSink = new Reaper(tracker, memory, sink)
        def ownRef = new TaskRef('T-own')
        def open = [
            working('T-own', version('m1')),
            working('T-foreign', version('m2'))
        ]

        when:
        reaperWithSink.reapOnce([ownRef])

        then:
        1 * tracker.listOpen() >> open
        1 * sink.onListed(open)
        0 * sink.onListingFailed()
    }

    // FR3, NFR-R1 of add-serve-sandbox-lifecycle: a listOpen outage notifies the sink's failure
    //     path, never onListed — so a consumer never mistakes stale cached data for a fresh
    //     empty listing.
    def "notifies the sink of a listOpen outage instead of publishing a listing"() {
        given:
        def sink = Mock(OpenTaskListingSink)
        def reaperWithSink = new Reaper(tracker, memory, sink)

        when:
        reaperWithSink.reapOnce([])

        then:
        1 * tracker.listOpen() >> { throw new RuntimeException('tracker down') }
        1 * sink.onListingFailed()
        0 * sink.onListed(_)
    }

    // The 2-arg constructor keeps every pre-existing call site working unchanged (NONE sink).
    def "the 2-arg constructor uses the no-op sink"() {
        when:
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> [working('T-1', version())]
        noExceptionThrown()
    }
}
