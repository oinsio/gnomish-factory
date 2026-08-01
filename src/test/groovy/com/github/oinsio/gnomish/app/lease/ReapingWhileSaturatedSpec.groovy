package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR13 of add-factory-serve, "Reaping while saturated": wires the REAL {@link InstanceHeartbeat}
 * (task 6.1's beat thread) directly to a REAL {@link Reaper} over a {@link StalenessMemory} (task
 * 4.3), exactly as {@link com.github.oinsio.gnomish.app.TakeHeartbeat#forRun} assembles them for
 * {@code serve} — then drives one {@link InstanceHeartbeat#tick} while this instance holds every
 * slot's claim (modelling "all slots are busy") and a claim belonging to a DIFFERENT instance has
 * gone stale. The reaper duty runs on the heartbeat's own tick, never on {@code FeedAutomaton}'s
 * thread or state (see {@link InstanceHeartbeat}'s class javadoc) — so this holds equally whether
 * the feed is Full, Idle-blocked, or anything else; the scenario is exercised here at the tick
 * level rather than through a full {@code ServeCommand}/{@code FeedAutomaton} run, mirroring
 * {@link ReaperSpec}'s and {@link InstanceHeartbeatSpec}'s existing style (no real time, no
 * threading).
 *
 * <p>Implements FR13 of add-factory-serve; FR1, FR4, D13 of add-claim-heartbeat.
 */
class ReapingWhileSaturatedSpec extends Specification {

    private static final Duration INTERVAL = Duration.ofMinutes(5)
    private static final Duration TTL = Duration.ofMinutes(15)
    private static final TaskRef OWN_A = new TaskRef('github:o/r#own-a')
    private static final TaskRef OWN_B = new TaskRef('github:o/r#own-b')
    private static final TaskRef FOREIGN = new TaskRef('github:o/r#foreign')

    private final Tracker tracker = Mock()
    private final VirtualClock clock = new VirtualClock()
    private final VirtualMonotonicTime monotonic = new VirtualMonotonicTime()
    private final StalenessMemory staleness = new StalenessMemory(monotonic, TTL)
    private final Reaper reaper = new Reaper(tracker, staleness)
    // The real cross-slot heartbeat: every slot's held claim beats on this ONE thread's tick,
    // which also runs the reaper duty — the exact wiring ServeCommand assembles via TakeHeartbeat.
    private final InstanceHeartbeat heartbeat = new InstanceHeartbeat(
    tracker, new HeartbeatProgress(), new BlockingSleeper(), clock, INTERVAL,
    ClaimLostSink.IGNORE, reaper)

    private static ClaimVersion version(String marker) {
        new ClaimVersion(marker, Instant.EPOCH)
    }

    private static OpenTask workingBy(TaskRef ref, String instance, ClaimVersion v) {
        new OpenTask(ref, new TrackerTaskState.Working(instance), v)
    }

    def cleanup() {
        heartbeat.unregister(OWN_A)
        heartbeat.unregister(OWN_B)
    }

    // FR13 scenario "Reaping while saturated": both slots are busy (this instance holds OWN_A and
    // OWN_B — modelling a fully saturated WIP), and a foreign instance's claim goes stale. One beat
    // tick still observes and, once its TTL has elapsed, removes the stale foreign claim, returning
    // it to Ready and lowering the open-front count — with no human involved and without either of
    // this instance's own busy claims ever being touched.
    def "a foreign claim gone stale is reaped within one beat tick while every slot is busy"() {
        given: 'every slot is busy: this instance holds both claims and beats them normally'
        heartbeat.register(OWN_A)
        heartbeat.register(OWN_B)
        def foreignVersion = version('foreign-m1')

        when: 'the first tick beats the two held claims and only observes the foreign one'
        heartbeat.tick()

        then:
        1 * tracker.heartbeat(OWN_A, _) >> new HeartbeatResult.Beaten(version('a'))
        1 * tracker.heartbeat(OWN_B, _) >> new HeartbeatResult.Beaten(version('b'))
        1 * tracker.listOpen() >> [
            workingBy(FOREIGN, 'gnomish-other', foreignVersion)
        ]
        0 * tracker.removeStaleClaim(_, _)

        when: 'the foreign claim TTL elapses with its version unchanged, on the very next tick'
        monotonic.advance(TTL)
        heartbeat.tick()

        then: 'the stale foreign claim is reaped within this one tick — Working returns to Ready'
        1 * tracker.heartbeat(OWN_A, _) >> new HeartbeatResult.Beaten(version('a'))
        1 * tracker.heartbeat(OWN_B, _) >> new HeartbeatResult.Beaten(version('b'))
        1 * tracker.listOpen() >> [
            workingBy(FOREIGN, 'gnomish-other', foreignVersion)
        ]
        1 * tracker.removeStaleClaim(FOREIGN, foreignVersion) >> new RemoveStaleClaimResult.Removed()

        and: "this instance's own busy claims are never reaped, saturated or not"
        0 * tracker.removeStaleClaim(OWN_A, _)
        0 * tracker.removeStaleClaim(OWN_B, _)
    }
}
