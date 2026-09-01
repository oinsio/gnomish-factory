package com.github.oinsio.gnomish.app.lease

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.logtext.ShutdownPhase
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.Timeout

/**
 * FR9 of harden-logging-observability, factory-serve "Shutdown-caused death is not an alarm": the
 * heartbeat worker dies the same way whether an adapter blew the stack or the daemon's own stop
 * interrupted it — but only one of those is an operator's problem. A clean SIGTERM that ends every
 * healthy run with an ERROR nobody can act on is what trains an operator to stop reading the level.
 *
 * <p>Both branches are driven through the REAL worker (the rendezvous {@link BlockingSleeper}
 * shape {@code InstanceHeartbeatDirtyNotifierSpec} uses), because the classification lives in the
 * uncaught-exception handler and only a genuine abnormal exit reaches it.
 */
@Timeout(10)
class InstanceHeartbeatDeathClassificationSpec extends Specification {

    private static final Duration INTERVAL = Duration.ofMinutes(5)
    private static final TaskRef REF = new TaskRef('github:o/r#1')

    private final Tracker tracker = Stub(Tracker) {
        heartbeat(_, _) >> new HeartbeatResult.Beaten(new ClaimVersion('m', Instant.EPOCH, new ClaimEpoch(1)))
    }
    private final HeartbeatProgress progress = new HeartbeatProgress()
    private final List<InstanceHeartbeat> started = []

    def setup() {
        ShutdownPhase.reset()
        progress.onEvent(new EngineEvent.AttemptStarted(new AttemptKey(REF.id(), 'plan', 0)))
    }

    def cleanup() {
        ShutdownPhase.reset()
        // Drain every started worker's held set so it terminates on its next pass, rather than
        // leaking a spinning thread into PIT's reused minion (see InstanceHeartbeatSpec).
        started.each { it.unregister(REF) }
    }

    /** Drives one heartbeat to an abnormal worker death and returns the lines it wrote. */
    private List<Level> levelsOfDeath(List<String> messages) {
        def base = new BlockingSleeper()
        def calls = new AtomicInteger()
        def dyingSleeper = { Duration d ->
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException('the worker stopped beating')
            }
            base.sleep(d)
        } as Sleeper
        def dying = new InstanceHeartbeat(
                tracker, progress, dyingSleeper, new VirtualClock(), INTERVAL, ClaimLostSink.IGNORE)
        started << dying
        def capture = LogCaptureSupport.attach(InstanceHeartbeat)
        try {
            dying.register(REF)
            def deadWorker = dying.worker()
            base.awaitEntered()
            base.releaseOne()
            deadWorker.join()
            messages.addAll(capture.list*.formattedMessage)
            return capture.list*.level
        } finally {
            capture.detach()
        }
    }

    def "FR9: a death nobody asked for stays an ERROR"() {
        given:
        def messages = []

        when:
        def levels = levelsOfDeath(messages)

        then:
        levels == [Level.ERROR]
        messages[0].contains('died; held claims will go stale and be reaped')
    }

    def "FR9: a death the stop caused is one WARN naming the shutdown"() {
        given:
        def messages = []
        ShutdownPhase.begin()

        when:
        def levels = levelsOfDeath(messages)

        then: 'no ERROR blames the application, and the line says what really happened'
        levels == [Level.WARN]
        messages[0].contains('stopped by the daemon shutdown')
        messages[0].contains('IllegalStateException')
    }
}
