package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * StandingReaper's synchronous {@code tick()} seam (design D1, D2, D4): a standing duty that
 * lives for the whole run, independent of whether the instance holds any claim, and delegates
 * each tick to the real {@link ReaperDuty}. This spec drives {@code tick()} directly — no
 * thread, no real sleeping — with an empty live-claims snapshot and a REAL {@link Reaper}
 * wired to a mocked {@link Tracker}, mirroring {@code ReaperSpec}'s style: a foreign {@code
 * Working} claim whose version stands unchanged past the TTL must be reaped even though this
 * instance holds no claim at all (FR1 "reaping continues while the instance holds no claim").
 *
 * FR1, FR2 of fix-reaper-idle-liveness; design D1.
 */
class StandingReaperSpec extends Specification {

    private static final Duration TTL = Duration.ofMinutes(15)
    private static final Duration INTERVAL = Duration.ofMinutes(5)
    private static final Instant ANCIENT = Instant.parse('2000-01-01T00:00:00Z')
    private static final TaskRef FOREIGN = new TaskRef('T-foreign')

    private final Tracker tracker = Mock(Tracker) { listReady(_) >> [] }
    private final VirtualMonotonicTime time = new VirtualMonotonicTime()
    private final StalenessMemory memory = new StalenessMemory(time, TTL)
    private final Reaper reaper = new Reaper(tracker, memory)

    // No claim held for the whole spec: the empty snapshot is what proves the standing reaper
    // ticks even though this instance holds nothing (FR1), unlike the old beat-riding reaper
    // which only ran while InstanceHeartbeat's thread was alive.
    private final StandingReaper standingReaper =
    new StandingReaper(reaper, { Duration d -> }, INTERVAL, {
        []
    }, new SystemClock())

    private static OpenTask working(String ref, ClaimVersion version) {
        new OpenTask(new TaskRef(ref), new TrackerTaskState.Working('other-instance'), version, 'fixture title')
    }

    private static ClaimVersion version() {
        new ClaimVersion('m1', ANCIENT, new ClaimEpoch(1))
    }

    // FR1, FR2, D1: with an empty live-claims snapshot (this instance holds no claim), a single
    //     tick still observes the foreign claim; once its version has stood unchanged for a
    //     full TTL a later tick removes it — the removal alone is what returns the task to
    //     Ready (Reaper never claims a reaped task for itself, per design D5).
    def "reaps a foreign stale claim and the task returns to Ready, though the instance holds no claim"() {
        given:
        def v = version()
        def open = [working('T-foreign', v)]

        when: 'the first tick observes the foreign claim as first-seen'
        standingReaper.tick()

        then:
        1 * tracker.listOpen() >> open
        0 * tracker.removeStaleClaim(_, _)

        when: 'a full TTL elapses with the version unchanged; another tick runs'
        time.advance(TTL)
        standingReaper.tick()

        then: 'the stale claim is removed with its observed version, returning the task to Ready'
        1 * tracker.listOpen() >> open
        1 * tracker.removeStaleClaim(new TaskRef('T-foreign'), new ClaimFacts.Live('other-instance', v)) >> new RemoveStaleClaimResult.Removed()
        0 * tracker.claim(FOREIGN, _)
    }
}
