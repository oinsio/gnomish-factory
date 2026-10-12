package com.github.oinsio.gnomish.app.lease

import static com.github.oinsio.gnomish.app.lease.ClaimFixtures.claimBy
import static com.github.oinsio.gnomish.app.lease.ClaimFixtures.version
import static com.github.oinsio.gnomish.app.lease.ClaimFixtures.workingBy

import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.Timeout

/**
 * `tracker-take` "Take runs the heartbeat thread and the reaper duty": the reaper duty does not
 * ride the beat. The REAL {@link InstanceHeartbeat} worker is driven to an abnormal death (an
 * uncaught {@code Error} from its sleeper, the shape {@link DeadHeartbeatReapingSpec} uses), and
 * the {@link StandingReaper} — wired exactly as {@link com.github.oinsio.gnomish.app.TakeHeartbeat#forRun}
 * wires it for {@code take} — still observes and removes a foreign claim that went stale meanwhile.
 * The reaper is ticked directly, as its sibling specs tick it; that {@code take} runs those ticks
 * on the reaper's own thread for the whole invocation is pinned by
 * {@code TakeCommandStandingReaperWiringSpec}.
 *
 * <p>FR1, FR5 of fix-reaper-idle-liveness.
 */
@Timeout(10)
class ReapingOutlivesDeadBeatSpec extends Specification {

    private static final Duration INTERVAL = Duration.ofMinutes(5)
    private static final Duration TTL = Duration.ofMinutes(15)
    private static final TaskRef OWN = new TaskRef('github:o/r#own')
    private static final TaskRef FOREIGN = new TaskRef('github:o/r#foreign')

    private final Tracker tracker = Mock(Tracker) { listReady(_) >> [] }
    private final VirtualMonotonicTime monotonic = new VirtualMonotonicTime()
    private final Reaper reaper = new Reaper(tracker, new StalenessMemory(monotonic, TTL))
    // Rendezvous for the first sleep, an uncaught Error on the second: one beat, then death.
    private final BlockingSleeper base = new BlockingSleeper()
    private final AtomicInteger calls = new AtomicInteger()
    private final Sleeper dyingSleeper = { Duration d ->
        if (calls.incrementAndGet() == 2) {
            throw new StackOverflowError('adapter blew the stack')
        }
        base.sleep(d)
    } as Sleeper
    private final InstanceHeartbeat heartbeat = new InstanceHeartbeat(
    tracker, new HeartbeatProgress(), VirtualTimeEquipment.on(new VirtualClock(), dyingSleeper), INTERVAL,
    ClaimLostSink.IGNORE)
    private final StandingReaper standingReaper =
    new StandingReaper(reaper, INTERVAL, heartbeat.&liveClaimsSnapshot, VirtualTimeEquipment.on(new VirtualClock(), { Duration d -> }))

    def cleanup() {
        heartbeat.unregister(OWN)
    }

    // FR1, FR5: a dead beat thread takes no reaping with it.
    // Scenario: tracker/tracker-take / Reaping outlives the beat thread
    def "the standing reaper still removes a stale foreign claim after the beat thread died abnormally"() {
        given: 'the run holds its own claim and the real beat thread beats it once'
        heartbeat.register(OWN)
        def worker = heartbeat.worker()
        def foreignVersion = version('foreign-m1')
        base.awaitEntered()

        when: 'the beat thread dies abnormally on its next sleep'
        base.releaseOne()
        worker.join()

        then: 'the one beat landed, and the beat thread is gone'
        1 * tracker.heartbeat(OWN, _) >> new HeartbeatResult.Beaten(version('own'))
        !worker.isAlive()

        when: 'the standing reaper sights a foreign claim'
        standingReaper.tick()

        then:
        1 * tracker.listOpen() >> [
            workingBy(FOREIGN, 'gnomish-other', foreignVersion)
        ]
        0 * tracker.removeStaleClaim(_, _)

        when: 'the foreign claim goes stale — its version unchanged for a whole TTL — and the reaper ticks again'
        monotonic.advance(TTL)
        standingReaper.tick()

        then: 'the stale foreign claim is removed, with no beat thread alive anywhere in the run'
        1 * tracker.listOpen() >> [
            workingBy(FOREIGN, 'gnomish-other', foreignVersion)
        ]
        1 * tracker.removeStaleClaim(FOREIGN, claimBy('gnomish-other', foreignVersion)) >> new RemoveStaleClaimResult.Removed()
        0 * tracker.heartbeat(_, _)
    }
}
