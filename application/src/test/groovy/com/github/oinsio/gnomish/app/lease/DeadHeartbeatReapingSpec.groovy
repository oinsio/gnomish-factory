package com.github.oinsio.gnomish.app.lease

import static com.github.oinsio.gnomish.app.lease.ClaimFixtures.version
import static com.github.oinsio.gnomish.app.lease.ClaimFixtures.workingBy

import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.Timeout

/**
 * Claim-heartbeat scenario "A dead heartbeat stops shielding its instance's claims" (FR2 of
 * fix-reaper-idle-liveness): combines the REAL {@link InstanceHeartbeat} worker thread (task
 * 2.1-2.3's abnormal-death path) with a REAL {@link StandingReaper}, exactly as {@link
 * ReapingWhileSaturatedSpec} and {@link ReapingWhileIdleSpec} wire them for {@code serve}. Unlike
 * those two, this spec drives the heartbeat's OWN worker thread to death on an uncaught {@code
 * Error} (a throwing sleeper, mirroring {@link InstanceHeartbeatLifecycleSpec}) rather than
 * calling {@code tick()} synchronously, because {@code onWorkerDeath} only fires as the real
 * {@code Thread.UncaughtExceptionHandler} — a manual {@code tick()} call can never trigger it.
 *
 * <p>Scope note: this spec proves only the FIRST half of the scenario — the instance's own
 * standing reaper returns the claim to {@code Ready} once its heartbeat has died and the TTL
 * elapses. The scenario's closing clause (neutralizing the now-zombie slot) is covered elsewhere:
 * the ordinary revocation/fence path handles the not-re-claimed and foreign-re-claim cases, and a
 * same-instance re-claim cannot arise at all — the feed skips candidates still occupying a local
 * slot (design D6 of fix-reaper-idle-liveness, {@code FeedCycleSpec}).
 *
 * <p>Implements FR2 of fix-reaper-idle-liveness.
 */
@Timeout(10)
class DeadHeartbeatReapingSpec extends Specification {

    private static final Duration INTERVAL = Duration.ofMinutes(5)
    private static final Duration TTL = Duration.ofMinutes(15)
    private static final TaskRef OWN = new TaskRef('github:o/r#own')
    private static final String SELF_INSTANCE = 'gnomish-self'

    private final Tracker tracker = Mock()
    private final VirtualMonotonicTime monotonic = new VirtualMonotonicTime()
    private final StalenessMemory staleness = new StalenessMemory(monotonic, TTL)
    private final Reaper reaper = new Reaper(tracker, staleness)
    // A sleeper that behaves like the rendezvous BlockingSleeper for its first two calls, then
    // throws an uncaught Error on the third — mirroring InstanceHeartbeatLifecycleSpec's abnormal
    // death path, but leaving room for one extra beat/sleep round while the worker is still alive
    // so the spec can prove shielding BEFORE forcing the death.
    private final BlockingSleeper base = new BlockingSleeper()
    private final AtomicInteger calls = new AtomicInteger()
    private final Sleeper dyingSleeper = { Duration d ->
        if (calls.incrementAndGet() == 3) {
            throw new StackOverflowError('adapter blew the stack')
        }
        base.sleep(d)
    } as Sleeper
    private final InstanceHeartbeat heartbeat = new InstanceHeartbeat(
    tracker, new HeartbeatProgress(), dyingSleeper, new VirtualClock(), INTERVAL,
    ClaimLostSink.IGNORE)
    // Wired exactly like TakeHeartbeat#forRun: the standing reaper's live-claims supplier is this
    // SAME heartbeat, so OWN is excluded from staleness observation only while it is actually
    // beating.
    private final StandingReaper standingReaper =
    new StandingReaper(reaper, { Duration d -> }, INTERVAL, heartbeat.&liveClaimsSnapshot, new VirtualClock())

    def cleanup() {
        heartbeat.unregister(OWN)
    }

    // FR2, claim-heartbeat "A dead heartbeat stops shielding its instance's claims": while the
    // real worker thread is alive and beating OWN, the standing reaper never reaps it — not even
    // once a full TTL has elapsed on the monotonic clock, because a live claim is filtered out
    // before staleness is ever observed (design D13). Once the worker dies ABNORMALLY (an
    // uncaught Error from the sleeper, exactly the shape onWorkerDeath handles) and
    // liveClaimsSnapshot() empties, the SAME still-held, still-Working claim is no longer
    // excluded: the standing reaper starts a fresh observation window on its next tick and, once
    // TTL elapses on THAT window, reaps it — returning the task to Ready. The contrast between
    // "TTL elapsed while alive: never reaped" and "TTL elapsed after death: reaped" makes the
    // shielding-then-loss-of-shielding causality explicit, not just a restatement of the
    // idle-instance case.
    def "a dead heartbeat's still-held claim is no longer shielded and is reaped by the standing reaper"() {
        given: 'the claim registers and the real worker thread starts beating it'
        heartbeat.register(OWN)
        def worker = heartbeat.worker()
        def v = version('m1')

        when: 'the first interval elapses and the worker beats OWN once, then parks in its next sleep'
        base.awaitEntered()
        base.releaseOne()
        base.awaitEntered()

        then: 'the beat landed and the worker is still alive, still shielding OWN'
        1 * tracker.heartbeat(OWN, _) >> new HeartbeatResult.Beaten(v)
        worker.isAlive()
        heartbeat.liveClaimsSnapshot() == [OWN] as Set

        when: 'the standing reaper first sights OWN while the heartbeat is alive'
        standingReaper.tick()

        then: 'excluded as a live claim: not even observed for staleness'
        1 * tracker.listOpen() >> [
            workingBy(OWN, SELF_INSTANCE, v)
        ]
        0 * tracker.removeStaleClaim(_, _)

        when: 'a full TTL elapses on the monotonic clock while the heartbeat stays alive'
        monotonic.advance(TTL)
        standingReaper.tick()

        then: 'still shielded: elapsed time never accrues for an excluded, still-live claim'
        1 * tracker.listOpen() >> [
            workingBy(OWN, SELF_INSTANCE, v)
        ]
        0 * tracker.removeStaleClaim(_, _)

        when: 'the worker beats once more, then dies abnormally on its next sleep'
        base.releaseOne()
        worker.join()

        then: 'the second beat landed and the worker is dead, so the snapshot no longer shields OWN'
        1 * tracker.heartbeat(OWN, _) >> new HeartbeatResult.Beaten(v)
        !worker.isAlive()
        heartbeat.liveClaimsSnapshot().isEmpty()

        when: 'the standing reaper first sights the now-unshielded OWN post-death'
        standingReaper.tick()

        then: 'a fresh observation window starts this tick; not yet stale'
        1 * tracker.listOpen() >> [
            workingBy(OWN, SELF_INSTANCE, v)
        ]
        0 * tracker.removeStaleClaim(_, _)

        when: 'a full TTL elapses on that fresh window with the version unchanged (no beat is landing anymore)'
        monotonic.advance(TTL)
        standingReaper.tick()

        then: 'the instance\'s own standing reaper returns the now-unbeaten claim to Ready'
        1 * tracker.listOpen() >> [
            workingBy(OWN, SELF_INSTANCE, v)
        ]
        1 * tracker.removeStaleClaim(OWN, v) >> new RemoveStaleClaimResult.Removed()
    }
}
