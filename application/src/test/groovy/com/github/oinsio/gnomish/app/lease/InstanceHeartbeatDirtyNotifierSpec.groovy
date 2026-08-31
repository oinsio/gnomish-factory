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
 * InstanceHeartbeat's {@link HeartbeatStateListener} trigger point (FR1, FR7, design D4 of
 * add-serve-observability): every transition of {@link InstanceHeartbeat#state()} — worker start
 * (→ {@code RUNNING}), abnormal death (→ {@code DIED}), and the normal idle stop (→ {@code IDLE}) —
 * wakes the snapshot writer immediately rather than waiting for its timer beat, so a heartbeat
 * death lands {@code vitals.heartbeat.state: died} at once (design D4: the timer alone is not
 * enough). A register onto an already-running worker changes only {@code heldClaims} (which MAY lag
 * a beat), not the state, so it must not fire a spurious write; and a throwing listener must never
 * break beating (NFR-R1). Mirrors {@code SlotLedgerDirtyNotifierSpec}.
 *
 * <p>Implements FR1, FR7 of add-serve-observability.
 */
@Timeout(10)
class InstanceHeartbeatDirtyNotifierSpec extends Specification {

    private static final Duration INTERVAL = Duration.ofMinutes(5)
    private static final TaskRef A = new TaskRef('github:o/r#1')
    private static final TaskRef B = new TaskRef('github:o/r#2')
    private static final HeartbeatResult BEATEN = new HeartbeatResult.Beaten(new ClaimVersion('m', Instant.EPOCH, new ClaimEpoch(1)))

    private final Tracker tracker = Stub(Tracker) {
        heartbeat(_, _) >> BEATEN
    }
    private final HeartbeatProgress progress = new HeartbeatProgress()
    private final VirtualClock clock = new VirtualClock()
    private final AtomicInteger fired = new AtomicInteger()
    private final HeartbeatStateListener listener = {
        -> fired.incrementAndGet()
    } as HeartbeatStateListener
    // Every heartbeat a test starts a real worker on, drained in cleanup (see cleanup()).
    private final List<InstanceHeartbeat> started = []

    def cleanup() {
        // Drain every started worker's held set so it terminates on its next pass. This bounds a
        // sleep-dropping mutant's busy-spin (the worker checks the held set each cycle) rather than
        // leaking a spinning thread into PIT's reused minion — see InstanceHeartbeatSpec.
        started.each { it.unregister(A); it.unregister(B) }
    }

    // FR7: the worker's abnormal death — the death handler firing — is an immediate write trigger.
    //     Isolated from the earlier worker-start signal by resetting the counter once the worker is
    //     parked in its first sleep, so the count proves the DIED transition alone woke the writer.
    def "an abnormal worker death fires the state listener"() {
        given: 'a sleeper that throws on its second call, mimicking an adapter Error'
        def base = new BlockingSleeper()
        def calls = new AtomicInteger()
        def dyingSleeper = { Duration d ->
            if (calls.incrementAndGet() == 2) {
                throw new StackOverflowError('adapter blew the stack')
            }
            base.sleep(d)
        } as Sleeper
        def dying = new InstanceHeartbeat(tracker, progress, dyingSleeper, clock, INTERVAL, ClaimLostSink.IGNORE, listener)
        started << dying

        when: 'the claim registers, the worker parks, then dies on its second sleep'
        dying.register(A)
        def deadWorker = dying.worker()
        base.awaitEntered()
        fired.set(0)
        base.releaseOne()
        deadWorker.join()

        then: 'the RUNNING → DIED transition fired the listener exactly once'
        !deadWorker.isAlive()
        dying.state() == HeartbeatWorkerState.DIED
        fired.get() == 1
    }

    // FR1, FR7: the first claim starting the worker (IDLE → RUNNING) fires the trigger.
    def "the first claim starting the worker fires the state listener"() {
        when:
        hbWith(new BlockingSleeper()).register(A)

        then:
        fired.get() == 1
    }

    // FR1: a register onto an already-running worker changes only heldClaims — not a trigger (D11:
    //     that field may lag a beat) — so it fires nothing, avoiding a spurious write.
    def "a register onto an already-running worker fires nothing further"() {
        given:
        def sleeper = new BlockingSleeper()
        def hb = hbWith(sleeper)
        hb.register(A)
        sleeper.awaitEntered()
        fired.set(0)

        when: 'a second claim joins the same running worker'
        hb.register(B)

        then:
        fired.get() == 0
        hb.heldClaims() == 2
    }

    // FR1, FR7: the normal empty-set stop (RUNNING → IDLE) is a state transition too, so it fires.
    def "the normal idle stop fires the state listener"() {
        given:
        def sleeper = new BlockingSleeper()
        def hb = hbWith(sleeper)
        hb.register(A)
        def worker = hb.worker()
        sleeper.awaitEntered()
        fired.set(0)

        when: 'the only claim is dropped and the loop finds the held set empty'
        hb.unregister(A)
        sleeper.releaseOne()
        worker.join()

        then:
        hb.state() == HeartbeatWorkerState.IDLE
        fired.get() == 1
    }

    // NFR-R1 (task 3.6): a throwing listener must not break the heartbeat — register must still
    //     start a live worker and report RUNNING; the observability failure is swallowed.
    def "a throwing state listener does not break beating or the lifecycle"() {
        given:
        HeartbeatStateListener boom = {
            -> throw new RuntimeException('listener boom')
        }
        def sleeper = new BlockingSleeper()
        def hb = new InstanceHeartbeat(tracker, progress, sleeper, clock, INTERVAL, ClaimLostSink.IGNORE, boom)
        started << hb

        when:
        hb.register(A)
        sleeper.awaitEntered()

        then:
        noExceptionThrown()
        hb.worker().isAlive()
        hb.state() == HeartbeatWorkerState.RUNNING

        cleanup:
        hb.unregister(A)
        sleeper.releaseOne()
        hb.worker().join()
    }

    // The six-arg constructor defaults to the no-op IGNORE listener — every caller predating the
    // serve observability writer keeps working with no snapshot writer to wake.
    def "the six-arg constructor defaults to the no-op listener"() {
        given:
        def sleeper = new BlockingSleeper()
        def hb = new InstanceHeartbeat(tracker, progress, sleeper, clock, INTERVAL, ClaimLostSink.IGNORE)
        started << hb

        when:
        hb.register(A)
        sleeper.awaitEntered()
        hb.unregister(A)
        sleeper.releaseOne()
        hb.worker().join()

        then:
        noExceptionThrown()
    }

    private InstanceHeartbeat hbWith(Sleeper sleeper) {
        def hb = new InstanceHeartbeat(tracker, progress, sleeper, clock, INTERVAL, ClaimLostSink.IGNORE, listener)
        started << hb
        hb
    }
}
