package com.github.oinsio.gnomish.app.lease

import static com.github.oinsio.gnomish.app.lease.ClaimFixtures.claimBy
import static com.github.oinsio.gnomish.app.lease.ClaimFixtures.version
import static com.github.oinsio.gnomish.app.lease.ClaimFixtures.workingBy

import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.time.Duration
import spock.lang.Specification

/**
 * FR13 of add-factory-serve, "Reaping while idle with no claims of its own" — the opposite
 * extreme from {@link ReapingWhileSaturatedSpec}: an instance that has registered NOTHING with
 * its {@link InstanceHeartbeat} at all, exactly the shape of a `serve` daemon sitting
 * Idle-empty or one that has just restarted before its first claim. Wires the REAL {@link
 * InstanceHeartbeat} (never {@code register}ed, so {@link InstanceHeartbeat#liveClaimsSnapshot}
 * stays empty) as the live-claims supplier for a REAL {@link StandingReaper}, exactly as {@link
 * com.github.oinsio.gnomish.app.TakeHeartbeat#forRun} assembles them — proving the standing
 * reaper's tick needs no claim of its own to observe and remove a foreign stale claim.
 *
 * <p>Implements FR13 of add-factory-serve; FR1, FR2 of fix-reaper-idle-liveness.
 */
class ReapingWhileIdleSpec extends Specification {

    private static final Duration INTERVAL = Duration.ofMinutes(5)
    private static final Duration TTL = Duration.ofMinutes(15)
    private static final TaskRef FOREIGN = new TaskRef('github:o/r#foreign')

    private final Tracker tracker = Mock(Tracker) { listReady(_) >> [] }
    private final VirtualMonotonicTime monotonic = new VirtualMonotonicTime()
    private final StalenessMemory staleness = new StalenessMemory(monotonic, TTL)
    private final Reaper reaper = new Reaper(tracker, staleness)
    // Never register()ed: liveClaimsSnapshot() stays empty for the whole spec, modelling
    // Idle-empty / just-restarted with zero claims of this instance's own.
    private final InstanceHeartbeat heartbeat = new InstanceHeartbeat(
    tracker, new HeartbeatProgress(), new BlockingSleeper(), new VirtualClock(),
    INTERVAL, ClaimLostSink.IGNORE)
    private final StandingReaper standingReaper =
    new StandingReaper(reaper, { Duration d -> }, INTERVAL, heartbeat.&liveClaimsSnapshot, new VirtualClock())

    // FR13 "Reaping while idle with no claims of its own": with the instance holding nothing —
    // its heartbeat never started, so the live-claims snapshot is empty on every tick — a
    // foreign Working claim still gets observed and, once its TTL elapses, reaped within one
    // reaper interval, returning the task to Ready without this instance ever claiming
    // anything first.
    def "reaps a foreign stale claim within one reaper interval though the instance holds nothing"() {
        given: 'the instance never registered any claim of its own'
        assert heartbeat.liveClaimsSnapshot().isEmpty()
        def foreignVersion = version('foreign-m1')

        when: 'the first tick, with an empty live-claims snapshot, only observes the foreign claim'
        standingReaper.tick()

        then:
        1 * tracker.listOpen() >> [
            workingBy(FOREIGN, 'gnomish-other', foreignVersion)
        ]
        0 * tracker.removeStaleClaim(_, _)
        heartbeat.liveClaimsSnapshot().isEmpty()

        when: 'a full TTL elapses with the version unchanged; the next tick runs'
        monotonic.advance(TTL)
        standingReaper.tick()

        then: 'the foreign claim is reaped, returning its task to Ready'
        1 * tracker.listOpen() >> [
            workingBy(FOREIGN, 'gnomish-other', foreignVersion)
        ]
        1 * tracker.removeStaleClaim(FOREIGN, claimBy('gnomish-other', foreignVersion)) >> new RemoveStaleClaimResult.Removed()

        and: 'this instance never held or claimed anything at any point'
        heartbeat.liveClaimsSnapshot().isEmpty()
        0 * tracker.claim(_, _)
    }
}
