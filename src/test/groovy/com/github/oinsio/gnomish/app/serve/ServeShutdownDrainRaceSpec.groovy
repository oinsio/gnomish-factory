package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ServeShutdown grace-window drain races (FR11, D9, M3) driven by real threads: a slot that reaches
 * its round boundary within the grace window is released while claiming stops immediately, and an
 * interrupt landing while shutdown() is parked in awaitDrained restores the interrupt status and
 * still completes the sequence. Shared fixtures live in {@link ServeShutdownSpecBase}.
 *
 * Implements FR11, D9, M3 of add-factory-serve.
 */
class ServeShutdownDrainRaceSpec extends ServeShutdownSpecBase {

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
        def shutdown = new ServeShutdown(ledger, flag, grace, killer, inertReaper())
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
        Tracker fakeTracker = [listReady: { int limit ->
                listReadyCalls.incrementAndGet(); []
            }] as Tracker
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
        def shutdown = new ServeShutdown(ledger, flag, grace, killer, inertReaper())
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
            // The latch fires before the thread's last bytecode runs, so isAlive() can still race
            // true on a loaded machine — join() is the only wait that means "fully terminated".
            shutdownThread.join(5000)
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
}
