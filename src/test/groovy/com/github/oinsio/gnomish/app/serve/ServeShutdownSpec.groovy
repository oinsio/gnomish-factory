package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * ServeShutdown: the SIGTERM sequence (FR11, design D9, M3) — interrupt the feed thread, flag
 * every occupied slot's claim as gracefully stopping in the SAME {@link ClaimLossFlag} the
 * round-boundary check already consults, wait up to the grace window, then always kill the
 * process tree. The process-tree step is seamed behind {@link ProcessTreeKiller} so sequencing is
 * provable without spawning or killing real OS processes (a fake {@link ServeShutdownSpec.RecordingKiller}
 * stands in here; {@link RealProcessTreeKiller} is the untested production implementation).
 *
 * Implements FR11, D9, M3 of add-factory-serve.
 */
class ServeShutdownSpec extends Specification {

    private static final TaskRef A = new TaskRef('github:o/r#1')
    private static final TaskRef B = new TaskRef('github:o/r#2')

    private static class RecordingKiller implements ProcessTreeKiller {
        final AtomicInteger calls = new AtomicInteger()

        @Override
        void killDescendants() {
            calls.incrementAndGet()
        }
    }

    // Captures ServeShutdown's log output so the grace-window summary line — the only observable
    // effect of awaitDrainedQuietly's boolean result and of the "any slot occupied" branch guarding
    // it (line 90) — can be asserted on directly, the same pattern ReaperSpec uses.
    private static List<ILoggingEvent> capture(Closure<Void> emit) {
        LogbackLogger logbackLogger = (LogbackLogger) LoggerFactory.getLogger(ServeShutdown)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
    }

    // FR11: stopping claims immediately is the very first step, ahead of any flagging or waiting.
    def "interrupts the feed thread and eventually kills the process tree"() {
        given:
        def ledger = new SlotLedger(1)
        def flag = new ClaimLossFlag()
        def killer = new RecordingKiller()
        def shutdown = new ServeShutdown(ledger, flag, Duration.ofMillis(200), killer)
        def feedThread = Thread.ofVirtual().unstarted({
            try {
                Thread.sleep(5000)
            } catch (InterruptedException ignored) {
                // expected: the shutdown sequence interrupts this thread
            }
        } as Runnable)
        feedThread.start()

        when:
        shutdown.shutdown(feedThread)
        feedThread.join(2000)

        then:
        !feedThread.isAlive()
        killer.calls.get() == 1
    }

    // FR11: drain's post-drain normal exit passes null (nothing to interrupt) — must be a safe no-op.
    def "accepts a null feed thread and still kills the process tree"() {
        given:
        def ledger = new SlotLedger(1)
        def flag = new ClaimLossFlag()
        def killer = new RecordingKiller()
        def shutdown = new ServeShutdown(ledger, flag, Duration.ofMillis(50), killer)

        when:
        shutdown.shutdown(null)

        then:
        noExceptionThrown()
        killer.calls.get() == 1
    }

    // FR11, D9: every occupied slot's claim is flagged with the shutdown-specific reason, not the
    //     heartbeat's generic "claim marker gone" wording (message-accuracy motivation for reusing
    //     ClaimLossFlag with a reason).
    def "flags every occupied slot's claim with the shutdown reason"() {
        given:
        def ledger = new SlotLedger(2)
        ledger.acquire()
        ledger.assign(A)
        ledger.acquire()
        ledger.assign(B)
        def flag = new ClaimLossFlag()
        def killer = new RecordingKiller()
        def shutdown = new ServeShutdown(ledger, flag, Duration.ofMillis(50), killer)

        when:
        shutdown.shutdown(null)

        then:
        flag.isLost(A)
        flag.isLost(B)
        flag.reason(A) == ServeShutdown.SHUTDOWN_REASON
        flag.reason(B) == ServeShutdown.SHUTDOWN_REASON

        cleanup:
        ledger.release(A)
        ledger.release(B)
    }

    // FR11: an already-empty ledger (nothing occupied) must not consume the whole grace window —
    //     awaitDrained returns immediately when every permit is already free.
    def "returns promptly when nothing is occupied, without waiting out a long grace window"() {
        given:
        def ledger = new SlotLedger(2)
        def flag = new ClaimLossFlag()
        def killer = new RecordingKiller()
        def shutdown = new ServeShutdown(ledger, flag, Duration.ofSeconds(30), killer)

        when:
        long startNanos = System.nanoTime()
        shutdown.shutdown(null)
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

        then:
        elapsedMillis < 5_000L
        killer.calls.get() == 1
    }

    // FR11: "tasks whose rounds outlive the grace window need no new mechanism" — the process tree
    //     is still killed once the grace window expires, even with a slot still occupied.
    def "kills the process tree even when the grace window expires with a slot still occupied"() {
        given:
        def ledger = new SlotLedger(1)
        ledger.acquire()
        ledger.assign(A)
        def flag = new ClaimLossFlag()
        def killer = new RecordingKiller()
        def shutdown = new ServeShutdown(ledger, flag, Duration.ofMillis(100), killer)

        when:
        shutdown.shutdown(null)

        then:
        killer.calls.get() == 1
        ledger.freeSlots() == 0

        cleanup:
        ledger.release(A)
    }

    // FR11, D9, M3: the integration scenario explicitly asked for — a fake long round. Two
    //     occupied slots stand in for two in-flight tasks; slot A's fake "round" checks
    //     ClaimLossFlag frequently (a short round) and reacts exactly like the real round-boundary
    //     check (RevocationCheckingAttemptPersistence/RevocationHandler, proven independently by
    //     their own specs) — releasing the ledger slot once flagged; slot B's fake round never
    //     checks inside the grace window (a long round) and only finishes long after. A fake feed
    //     thread stands in for FeedAutomaton, recording every Tracker#listReady call it makes so
    //     "claiming stops immediately" is directly observable. No real sleep stands in for
    //     production timing — the grace window itself is short (300ms) and every wait here is a
    //     bounded latch/join, not a fixed delay.
    def "a slot reaching its round boundary within grace is released; one that does not is left alone, and claiming stops immediately"() {
        given: 'two occupied slots'
        def ledger = new SlotLedger(2)
        ledger.acquire()
        ledger.assign(A)
        ledger.acquire()
        ledger.assign(B)
        def flag = new ClaimLossFlag()
        def killer = new RecordingKiller()
        def grace = Duration.ofMillis(300)
        def shutdown = new ServeShutdown(ledger, flag, grace, killer)
        def released = new ConcurrentLinkedQueue<TaskRef>()

        and: "slot A's fake round checks the flag frequently and reacts as soon as it is set"
        def slotAReleased = new CountDownLatch(1)
        Thread.ofVirtual().start({
            while (!flag.isLost(A)) {
                Thread.sleep(5)
            }
            released.add(A)
            ledger.release(A)
            slotAReleased.countDown()
        } as Runnable)

        and: "slot B's fake round never checks inside the grace window, finishing long after it"
        def slotBReleased = new CountDownLatch(1)
        Thread.ofVirtual().start({
            Thread.sleep(grace.toMillis() * 5)
            released.add(B)
            ledger.release(B)
            slotBReleased.countDown()
        } as Runnable)

        and: 'a fake feed thread standing in for FeedAutomaton, polling listReady until interrupted'
        def listReadyCalls = new AtomicInteger()
        Tracker fakeTracker = [listReady: { int limit -> listReadyCalls.incrementAndGet(); [] }] as Tracker
        def feedThread = Thread.ofVirtual().unstarted({
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    fakeTracker.listReady(10)
                    Thread.sleep(10)
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt()
            }
        } as Runnable)
        feedThread.start()
        Thread.sleep(30) // let the fake feed actually poll a few times before shutdown starts

        expect: 'claiming was genuinely happening before shutdown'
        listReadyCalls.get() > 0

        when:
        shutdown.shutdown(feedThread)

        then: 'A made it to its boundary within grace and was released; B did not'
        slotAReleased.await(2, TimeUnit.SECONDS)
        released.contains(A)
        !released.contains(B)
        flag.reason(A) == ServeShutdown.SHUTDOWN_REASON

        and: 'the feed thread stopped promptly once interrupted'
        feedThread.join(2000)
        !feedThread.isAlive()

        and: 'no further claim attempts happen once the feed thread has actually stopped'
        int countAtStop = listReadyCalls.get()
        Thread.sleep(50)
        listReadyCalls.get() == countAtStop

        and: "the process tree is killed regardless of B's still-occupied slot"
        killer.calls.get() == 1
        ledger.freeSlots() == 1

        cleanup: "let B's fake round finish so it does not leak a thread past this spec"
        slotBReleased.await(5, TimeUnit.SECONDS)
    }

    // FR11, D9: awaitDrainedQuietly's true result (every occupied slot released within grace) must
    //     be observable, not just computed — this is what distinguishes it from the false case below
    //     and kills both boolean-return mutants on the same line.
    def "logs allReleased=true when the occupied slot drains within the grace window"() {
        given:
        def ledger = new SlotLedger(1)
        ledger.acquire()
        ledger.assign(A)
        def flag = new ClaimLossFlag()
        def killer = new RecordingKiller()
        def grace = Duration.ofMillis(500)
        def shutdown = new ServeShutdown(ledger, flag, grace, killer)

        and: "a fake round that reacts to the flag almost immediately, well inside the grace window"
        Thread.ofVirtual().start({
            while (!flag.isLost(A)) {
                Thread.sleep(5)
            }
            ledger.release(A)
        } as Runnable)

        when:
        List<ILoggingEvent> events = capture { shutdown.shutdown(null) }

        then:
        def summary = events.find { it.formattedMessage.contains('in-flight task') }
        summary != null
        summary.formattedMessage.contains('true')
        !summary.formattedMessage.contains('false')
    }

    // FR11, D9: the false counterpart — the grace window expires with the occupied slot never
    //     released, so awaitDrainedQuietly must be observed returning false, not true.
    def "logs allReleased=false when the grace window expires with the slot still occupied"() {
        given:
        def ledger = new SlotLedger(1)
        ledger.acquire()
        ledger.assign(A)
        def flag = new ClaimLossFlag()
        def killer = new RecordingKiller()
        def shutdown = new ServeShutdown(ledger, flag, Duration.ofMillis(50), killer)

        when:
        List<ILoggingEvent> events = capture { shutdown.shutdown(null) }

        then:
        def summary = events.find { it.formattedMessage.contains('in-flight task') }
        summary != null
        summary.formattedMessage.contains('false')
        !summary.formattedMessage.contains('true')

        cleanup:
        ledger.release(A)
    }

    // FR11, D9: awaitDrainedQuietly's catch(InterruptedException) branch (Thread.currentThread()
    //     .interrupt() + return false) has never executed unless something interrupts the thread
    //     that is blocked inside slotLedger.awaitDrained(grace). This test occupies a slot that is
    //     never released, so the underlying Semaphore#tryAcquire blocks for the whole (long) grace
    //     window; shutdown() runs on a dedicated thread, which the test interrupts while it is
    //     parked there. Asserts both effects of the catch block: (a) shutdown() still returns
    //     promptly instead of hanging or propagating, still killing the process tree, and (b) the
    //     interrupt status was restored on that same thread — captured right after shutdown()
    //     returns, since Thread.currentThread().interrupt() sets the flag on the thread running
    //     awaitDrainedQuietly, not on the test's own thread.
    def "restores the interrupt status when interrupted while awaiting drain, and still completes shutdown"() {
        given:
        def ledger = new SlotLedger(1)
        ledger.acquire()
        ledger.assign(A)
        def flag = new ClaimLossFlag()
        def killer = new RecordingKiller()
        def grace = Duration.ofSeconds(10)
        def shutdown = new ServeShutdown(ledger, flag, grace, killer)
        def interruptedAfterShutdown = new AtomicInteger(-1)
        def shutdownReturned = new CountDownLatch(1)

        and: 'shutdown runs on its own thread so the test can interrupt it while it blocks in awaitDrained'
        def shutdownThread = Thread.ofPlatform().unstarted({
            shutdown.shutdown(null)
            interruptedAfterShutdown.set(Thread.currentThread().isInterrupted() ? 1 : 0)
            shutdownReturned.countDown()
        } as Runnable)

        when:
        def events = capture {
            shutdownThread.start()
            Thread.sleep(100) // let it get parked inside slotLedger.awaitDrained(grace)
            shutdownThread.interrupt()
            shutdownReturned.await(5, TimeUnit.SECONDS)
            return
        }

        then: 'shutdown returned promptly instead of hanging out the full 10s grace window'
        !shutdownThread.isAlive()

        and: 'the catch block ran: interrupt status was restored on the shutdown thread'
        interruptedAfterShutdown.get() == 1

        and: 'the process tree is still killed even though the wait was cut short by interruption'
        killer.calls.get() == 1

        and: 'awaitDrainedQuietly genuinely returned false on the interrupted path — logged, not just inferred'
        events.find { it.formattedMessage.endsWith(': false') }

        cleanup:
        ledger.release(A)
    }

    // FR11: line 90's guard (`if (!occupied.isEmpty())`) must actually matter — with nothing
    //     occupied at SIGTERM, no grace-window summary should be logged at all. A negated guard
    //     would flip this and log the (vacuous) summary instead.
    def "logs no grace-window summary when nothing was occupied at shutdown"() {
        given:
        def ledger = new SlotLedger(2)
        def flag = new ClaimLossFlag()
        def killer = new RecordingKiller()
        def shutdown = new ServeShutdown(ledger, flag, Duration.ofMillis(50), killer)

        when:
        List<ILoggingEvent> events = capture { shutdown.shutdown(null) }

        then:
        events.every { !it.formattedMessage.contains('in-flight task') }
    }
}
