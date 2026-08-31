package com.github.oinsio.gnomish.app.lease

import static com.github.oinsio.gnomish.app.lease.ClaimFixtures.claimBy
import static com.github.oinsio.gnomish.app.lease.ClaimFixtures.version
import static com.github.oinsio.gnomish.app.lease.ClaimFixtures.workingBy

import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.time.Duration
import spock.lang.Specification

/**
 * FR13 of add-factory-serve, "Reaping while saturated": wires a REAL {@link InstanceHeartbeat}
 * (task 6.1's beat thread) and a REAL {@link StandingReaper} over a {@link StalenessMemory}
 * (task 4.3) side by side, exactly as {@link com.github.oinsio.gnomish.app.TakeHeartbeat#forRun}
 * assembles them for {@code serve} — then drives one {@link InstanceHeartbeat#tick} and one
 * {@link StandingReaper#tick} while this instance holds every slot's claim (modelling "all slots
 * are busy") and a claim belonging to a DIFFERENT instance has gone stale. Beating and reaping
 * are two INDEPENDENT ticks on two independent objects (design D1 of fix-reaper-idle-liveness,
 * superseding the old design D4 where the reaper duty rode the heartbeat's own tick): the
 * standing reaper reads {@link InstanceHeartbeat#liveClaimsSnapshot} as its live-claims supplier,
 * which is what excludes this instance's own actively-beaten claims from staleness observation —
 * this holds equally whether the feed is Full, Idle-blocked, or anything else; the scenario is
 * exercised here at the tick level rather than through a full {@code ServeCommand}/{@code
 * FeedAutomaton} run, mirroring {@link ReaperSpec}'s and {@link InstanceHeartbeatSpec}'s existing
 * style (no real time, no threading).
 *
 * <p>Implements FR13 of add-factory-serve; FR1, FR4, D13 of add-claim-heartbeat; FR1, FR2 of
 * fix-reaper-idle-liveness.
 */
class ReapingWhileSaturatedSpec extends Specification {

    private static final Duration INTERVAL = Duration.ofMinutes(5)
    private static final Duration TTL = Duration.ofMinutes(15)
    private static final TaskRef OWN_A = new TaskRef('github:o/r#own-a')
    private static final TaskRef OWN_B = new TaskRef('github:o/r#own-b')
    private static final TaskRef FOREIGN = new TaskRef('github:o/r#foreign')

    private final Tracker tracker = Mock(Tracker) { listReady(_) >> [] }
    private final VirtualClock clock = new VirtualClock()
    private final VirtualMonotonicTime monotonic = new VirtualMonotonicTime()
    private final StalenessMemory staleness = new StalenessMemory(monotonic, TTL)
    private final Reaper reaper = new Reaper(tracker, staleness)
    // The real per-instance beat thread: every slot's held claim beats on this tick alone —
    // no reaper duty rides along, design D1 of fix-reaper-idle-liveness.
    private final InstanceHeartbeat heartbeat = new InstanceHeartbeat(
    tracker, new HeartbeatProgress(), new BlockingSleeper(), clock, INTERVAL,
    ClaimLostSink.IGNORE)
    // The standing reaper, wired exactly like TakeHeartbeat#forRun: its live-claims supplier is
    // this SAME heartbeat, so OWN_A/OWN_B are excluded from staleness observation only while the
    // heartbeat is actively beating them.
    private final StandingReaper standingReaper =
    new StandingReaper(reaper, { Duration d -> }, INTERVAL, heartbeat.&liveClaimsSnapshot, new VirtualClock())

    def cleanup() {
        heartbeat.unregister(OWN_A)
        heartbeat.unregister(OWN_B)
    }

    // FR13 scenario "Reaping while saturated": both slots are busy (this instance holds OWN_A and
    // OWN_B — modelling a fully saturated WIP), and a foreign instance's claim goes stale. The
    // heartbeat's own tick keeps beating both busy claims; the INDEPENDENT standing-reaper tick
    // still observes and, once its TTL has elapsed, removes the stale foreign claim, returning it
    // to Ready and lowering the open-front count — with no human involved and without either of
    // this instance's own busy claims ever being touched, by either tick.
    def "a foreign claim gone stale is reaped by a standing-reaper tick while every slot is busy"() {
        given: 'every slot is busy: this instance holds both claims and beats them normally'
        heartbeat.register(OWN_A)
        heartbeat.register(OWN_B)
        def foreignVersion = version('foreign-m1')

        when: 'the heartbeat beats the two held claims on its own tick'
        heartbeat.tick()

        then:
        1 * tracker.heartbeat(OWN_A, _) >> new HeartbeatResult.Beaten(version('a'))
        1 * tracker.heartbeat(OWN_B, _) >> new HeartbeatResult.Beaten(version('b'))

        when: 'the standing reaper ticks independently and only observes the foreign claim'
        standingReaper.tick()

        then:
        1 * tracker.listOpen() >> [
            workingBy(FOREIGN, 'gnomish-other', foreignVersion)
        ]
        0 * tracker.removeStaleClaim(_, _)

        when: 'the foreign claim TTL elapses with its version unchanged, on the next reaper tick'
        monotonic.advance(TTL)
        heartbeat.tick()
        standingReaper.tick()

        then: 'the stale foreign claim is reaped within this reaper tick — Working returns to Ready'
        1 * tracker.heartbeat(OWN_A, _) >> new HeartbeatResult.Beaten(version('a'))
        1 * tracker.heartbeat(OWN_B, _) >> new HeartbeatResult.Beaten(version('b'))
        1 * tracker.listOpen() >> [
            workingBy(FOREIGN, 'gnomish-other', foreignVersion)
        ]
        1 * tracker.removeStaleClaim(FOREIGN, claimBy('gnomish-other', foreignVersion)) >> new RemoveStaleClaimResult.Removed()

        and: "this instance's own busy claims are never reaped, saturated or not"
        0 * tracker.removeStaleClaim(OWN_A, _)
        0 * tracker.removeStaleClaim(OWN_B, _)
    }

    // The empty-snapshot case named by task 6.2: within this SAME saturated-suite harness, once
    // both of this instance's own claims are released — liveClaimsSnapshot() empties, exactly the
    // transition a slot going Idle after finishing its work would produce — the standing reaper
    // keeps reaping a foreign stale claim exactly as it did while excluding OWN_A/OWN_B, proving
    // the exclusion set shrinking to empty disturbs neither the reaper's behavior nor the
    // Mock-Tracker interaction shape already exercised above.
    def "reaps a foreign stale claim once this instance's own claims are released and the snapshot empties"() {
        given: 'this instance held both claims, then released them: the live-claims snapshot is now empty'
        heartbeat.register(OWN_A)
        heartbeat.register(OWN_B)
        heartbeat.unregister(OWN_A)
        heartbeat.unregister(OWN_B)
        assert heartbeat.liveClaimsSnapshot().isEmpty()
        def foreignVersion = version('foreign-m1')

        when: 'the standing reaper first sights the foreign claim with nothing of its own excluded'
        standingReaper.tick()

        then:
        1 * tracker.listOpen() >> [
            workingBy(FOREIGN, 'gnomish-other', foreignVersion)
        ]
        0 * tracker.removeStaleClaim(_, _)

        when: 'a full TTL elapses and the next standing-reaper tick runs'
        monotonic.advance(TTL)
        standingReaper.tick()

        then: 'the foreign claim is reaped, returning its task to Ready, exactly as while saturated'
        1 * tracker.listOpen() >> [
            workingBy(FOREIGN, 'gnomish-other', foreignVersion)
        ]
        1 * tracker.removeStaleClaim(FOREIGN, claimBy('gnomish-other', foreignVersion)) >> new RemoveStaleClaimResult.Removed()
    }
}
