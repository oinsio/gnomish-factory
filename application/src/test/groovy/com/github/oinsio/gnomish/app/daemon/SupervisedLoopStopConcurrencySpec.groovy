package com.github.oinsio.gnomish.app.daemon

import static com.github.oinsio.gnomish.app.daemon.SupervisedLoopHarness.CAP
import static com.github.oinsio.gnomish.app.daemon.SupervisedLoopHarness.INTERVAL
import static com.github.oinsio.gnomish.app.daemon.SupervisedLoopHarness.JOIN_BOUND

import ch.qos.logback.classic.Level
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.Timeout

/**
 * The races a stop runs against a respawn (design D4, D5 of
 * supervise-daemon-loops-and-embed-dashboard), on real threads per {@code lock-scope.md} "Specs":
 * the worker dies for real (a failure whose rendering throws escapes the guard), its death handler
 * sits in a {@link LatchedSleeper} backoff, and the spec stops the loop from another thread. The
 * threads a loop starts are observed through the ticks they run. A stop that meets a tick or a
 * wait is {@code SupervisedLoopStopRunConcurrencySpec}'s subject.
 */
@Timeout(20)
class SupervisedLoopStopConcurrencySpec extends Specification {

    private static final Duration PROMPT = Duration.ofMillis(500)
    private static final int RACES = 50

    private final List<SupervisedLoopHarness> rigs = []
    private final List<Thread> tickThreads = Collections.synchronizedList([])

    def cleanup() {
        rigs*.close()
    }

    // A loop whose first worker dies on its first tick; later ticks run {@code body} after
    // recording the thread they run on, then wait on {@code waitSleeper}.
    private SupervisedLoopHarness dyingLoop(LatchedSleeper backoff, LatchedSleeper waitSleeper,
            Closure body = {}) {
        def rig = new SupervisedLoopHarness(Level.INFO)
        rigs << rig
        rig.build(LoopOrder.TICK_THEN_WAIT, new LoopWait.FixedInterval(waitSleeper, INTERVAL), { n ->
            tickThreads << Thread.currentThread()
            if (n == 1) throw new SupervisedLoopHarness.Unrenderable()
            body(n)
        }, new RestartPolicy.Unbounded(INTERVAL, CAP), backoff)
        rig
    }

    private static boolean onHelper(Duration bound, Closure action) {
        Thread.ofVirtual().start(action).join(bound)
    }

    // FR4, NFR-R2 (daemon-supervision "Stop during a respawn backoff").
    def "a stop during a respawn backoff cuts it short and spawns no thread"() {
        given:
        def backoff = new LatchedSleeper(true)
        def rig = dyingLoop(backoff, new LatchedSleeper(true))
        rig.loop.start()
        assert backoff.awaitEntered()

        when:
        rig.loop.stop()

        then:
        backoff.returnedWithin(JOIN_BOUND)
        backoff.interrupted
        rig.stopAndJoinWithinBound()
        rig.ticks.get() == 1
        tickThreads.toSet().size() == 1
        rig.loop.restartCount() == 1
    }

    // FR4, NFR-R2 (daemon-supervision "Waiters are not blocked by a backoff"): even a backoff no
    //     stop can cut short holds no lock a stopping thread needs, and phase 3 still sees the stop.
    def "a stop returns promptly while the death handler sits in a latched backoff"() {
        given:
        def backoff = new LatchedSleeper(false)
        def rig = dyingLoop(backoff, new LatchedSleeper(true))
        rig.loop.start()
        assert backoff.awaitEntered()

        when:
        boolean prompt = onHelper(PROMPT) { rig.loop.stop() }
        boolean stillInBackoff = backoff.returned.count == 1
        backoff.release.countDown()

        then:
        prompt
        stillInBackoff
        backoff.interrupted
        rig.stopAndJoinWithinBound()
        rig.ticks.get() == 1
    }

    // FR4, NFR-R2 (daemon-supervision "Joining stop waits for the respawned thread").
    def "a joining stop returns only after the respawned thread exits"() {
        given:
        def backoff = new LatchedSleeper(true)
        def respawnedTicking = new CountDownLatch(1)
        def finishTick = new CountDownLatch(1)
        def rig = dyingLoop(backoff, new LatchedSleeper(true)) { n ->
            respawnedTicking.countDown()
            finishTick.await(JOIN_BOUND.toMillis(), TimeUnit.MILLISECONDS)
        }
        rig.loop.start()
        assert backoff.awaitEntered()
        backoff.release.countDown()
        assert respawnedTicking.await(JOIN_BOUND.toMillis(), TimeUnit.MILLISECONDS)

        when:
        def joiner = Thread.ofVirtual().start { rig.loop.stopAndJoin() }
        boolean returnedEarly = joiner.join(Duration.ofMillis(200))
        finishTick.countDown()
        boolean returned = joiner.join(JOIN_BOUND)

        then:
        !returnedEarly
        returned
        tickThreads.size() == 2
        tickThreads.every { !it.alive }
        rig.ticks.get() == 2
    }

    // FR4, NFR-R2: released backoff and joining stop start together; whichever wins, the call
    //     returns with every thread the loop started dead, and no tick runs after it returns.
    def "a joining stop racing a respawn leaves no thread alive and no tick after it"() {
        expect:
        (1..RACES).every { raceOnce() }
    }

    private boolean raceOnce() {
        tickThreads.clear()
        def backoff = new LatchedSleeper(true)
        def rig = dyingLoop(backoff, new LatchedSleeper(true))
        rig.loop.start()
        assert backoff.awaitEntered()
        def go = new CountDownLatch(1)
        def releaser = Thread.ofVirtual().start {
            go.await(); backoff.release.countDown()
        }
        def joiner = Thread.ofVirtual().start {
            go.await(); rig.loop.stopAndJoin()
        }
        go.countDown()
        assert joiner.join(JOIN_BOUND) && releaser.join(JOIN_BOUND)
        def threadsAtReturn = new ArrayList<Thread>(tickThreads)
        int ticksAtReturn = rig.ticks.get()
        assert threadsAtReturn.every { !it.alive }
        Thread.sleep(20)
        assert rig.ticks.get() == ticksAtReturn && ticksAtReturn in [1, 2]
        rigs.remove(rig)
        rig.close()
        true
    }
}
