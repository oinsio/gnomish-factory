package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.Timeout

/**
 * InstanceHeartbeat's vitals reader (task 2.5, add-serve-observability FR7): {@link
 * InstanceHeartbeat#state}, {@link InstanceHeartbeat#lastTickAt}, and {@link
 * InstanceHeartbeat#heldClaims} — the snapshot's {@code vitals.heartbeat} entry, driven
 * synchronously via {@code tick()} and the death/restart seams the sibling lifecycle spec
 * already exercises.
 *
 * <p>Implements FR7 of add-serve-observability.
 */
@Timeout(10)
class HeartbeatVitalsSpec extends Specification {

    private static final Duration INTERVAL = Duration.ofMinutes(5)
    private static final TaskRef A = new TaskRef('github:o/r#1')
    private static final TaskRef B = new TaskRef('github:o/r#2')
    private static final HeartbeatResult BEATEN = new HeartbeatResult.Beaten(new ClaimVersion('m', Instant.EPOCH, new ClaimEpoch(1)))

    private final Tracker tracker = Stub(Tracker) {
        heartbeat(_, _) >> BEATEN
    }
    private final HeartbeatProgress progress = new HeartbeatProgress()
    private final VirtualClock clock = new VirtualClock()
    // A parked sleeper: the auto-started worker blocks in its first sleep, so tick() is only
    // ever driven directly below — mirrors InstanceHeartbeatSpec's construction.
    private final InstanceHeartbeat hb =
    new InstanceHeartbeat(tracker, progress, new BlockingSleeper(), clock, INTERVAL, ClaimLostSink.IGNORE)

    def cleanup() {
        hb.unregister(A)
        hb.unregister(B)
    }

    // FR7: before any claim, the heartbeat reports IDLE.
    def "reports IDLE before the first claim"() {
        expect:
        hb.state() == HeartbeatWorkerState.IDLE
    }

    // FR7: once a claim starts the worker, the heartbeat reports RUNNING.
    def "reports RUNNING once a claim starts the worker"() {
        when:
        hb.register(A)

        then:
        hb.state() == HeartbeatWorkerState.RUNNING
    }

    // FR7, D3: the last-tick instant advances with every tick(), read from the injected clock —
    //     construction time before any tick.
    def "lastTickAt starts at construction time and advances with every tick"() {
        given:
        def constructedAt = hb.lastTickAt()
        hb.register(A)

        when:
        clock.advance(Duration.ofMinutes(5))
        hb.tick()

        then:
        hb.lastTickAt() == constructedAt + Duration.ofMinutes(5)
    }

    // FR7: heldClaims counts every registered claim regardless of worker state.
    def "heldClaims counts every currently held claim"() {
        when:
        hb.register(A)
        hb.register(B)

        then:
        hb.heldClaims() == 2

        when:
        hb.unregister(A)

        then:
        hb.heldClaims() == 1
    }

    // FR7, D3: an abnormal worker death reports DIED, distinct from the normal IDLE stop —
    //     the heartbeat's designed degradation death path becomes a field, not just a log line.
    def "reports DIED after an abnormal worker death, and RUNNING again after a later claim restarts it"() {
        given: 'a sleeper that throws on its second call, mimicking an adapter Error'
        def base = new BlockingSleeper()
        def calls = new AtomicInteger()
        def dyingSleeper = { Duration d ->
            if (calls.incrementAndGet() == 2) {
                throw new StackOverflowError('adapter blew the stack')
            }
            base.sleep(d)
        } as Sleeper
        def dying = new InstanceHeartbeat(tracker, progress, dyingSleeper, clock, INTERVAL, ClaimLostSink.IGNORE)

        when: 'the claim registers and the worker dies on its second sleep'
        dying.register(A)
        def deadWorker = dying.worker()
        base.awaitEntered()
        base.releaseOne()
        deadWorker.join()

        then:
        dying.state() == HeartbeatWorkerState.DIED

        when: 'a later claim of the same run restarts the worker'
        dying.register(A)
        base.awaitEntered()

        then:
        dying.state() == HeartbeatWorkerState.RUNNING

        cleanup:
        dying.unregister(A)
        base.releaseOne()
        dying.worker()?.join()
    }

    // FR7, D3: a claim registered, unregistered, and the loop stopping normally (no death)
    //     reports IDLE, not DIED — the two stop paths must stay distinguishable.
    def "reports IDLE, not DIED, after a normal stop with no held claims"() {
        given:
        def sleeper = new BlockingSleeper()
        def normal = new InstanceHeartbeat(tracker, progress, sleeper, clock, INTERVAL, ClaimLostSink.IGNORE)

        when: 'the only claim is dropped and the loop finds the held set empty'
        normal.register(A)
        def worker = normal.worker()
        sleeper.awaitEntered()
        normal.unregister(A)
        sleeper.releaseOne()
        worker.join()

        then:
        normal.state() == HeartbeatWorkerState.IDLE
    }
}
