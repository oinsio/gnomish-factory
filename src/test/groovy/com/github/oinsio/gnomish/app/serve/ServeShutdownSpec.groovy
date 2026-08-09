package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.adapter.engine.SystemClock
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.lease.ReaperDuty
import com.github.oinsio.gnomish.app.lease.StandingReaper
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

/**
 * ServeShutdown: the individual steps of the SIGTERM sequence (FR11, design D9, M3) — interrupt the
 * feed thread, flag every occupied slot's claim with the shutdown reason, always kill the process
 * tree, stay null-safe, return promptly when nothing is occupied, and stop the standing reaper.
 * Shared fixtures live in {@link ServeShutdownSpecBase}; the grace-window race and its summary line
 * have their own specs.
 *
 * Implements FR11, D9, M3 of add-factory-serve; fix-reaper-idle-liveness FR4.
 */
class ServeShutdownSpec extends ServeShutdownSpecBase {

    // FR11: stopping claims immediately is the very first step, ahead of any flagging or waiting.
    def "interrupts the feed thread and eventually kills the process tree"() {
        given:
        def ledger = new SlotLedger(1)
        def flag = new ClaimLossFlag()
        def killer = new RecordingKiller()
        def shutdown = new ServeShutdown(ledger, flag, Duration.ofMillis(200), killer, inertReaper())
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
        def shutdown = new ServeShutdown(ledger, flag, Duration.ofMillis(50), killer, inertReaper())

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
        def shutdown = new ServeShutdown(ledger, flag, Duration.ofMillis(50), killer, inertReaper())

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
        def shutdown = new ServeShutdown(ledger, flag, Duration.ofSeconds(30), killer, inertReaper())

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
        def shutdown = new ServeShutdown(ledger, flag, Duration.ofMillis(100), killer, inertReaper())

        when:
        shutdown.shutdown(null)

        then:
        killer.calls.get() == 1
        ledger.freeSlots() == 0

        cleanup:
        ledger.release(A)
    }

    // fix-reaper-idle-liveness FR4: shutdown() stops the standing reaper as part of the sequence
    // so its worker thread does not outlive the daemon. StandingReaper is a final class (not
    // mockable via Spock/CGLIB), so this drives a real one with a tick-counting ReaperDuty and
    // proves the observable effect of stop(): no further ticks happen once shutdown() returns.
    def "stops the standing reaper as part of the shutdown sequence"() {
        given:
        def ledger = new SlotLedger(1)
        def flag = new ClaimLossFlag()
        def killer = new RecordingKiller()
        def tickCount = new AtomicInteger()
        def reaperDuty = { Collection refs -> tickCount.incrementAndGet() } as ReaperDuty
        def sleeper = { Duration d -> Thread.sleep(5) } as Sleeper
        def standingReaper =
                new StandingReaper(reaperDuty, sleeper, Duration.ofMillis(5), { [] } as Supplier, new SystemClock())
        standingReaper.start()
        def shutdown = new ServeShutdown(ledger, flag, Duration.ofMillis(50), killer, standingReaper)

        when: 'let a handful of ticks happen before shutting down'
        Thread.sleep(50)
        int tickedBeforeShutdown = tickCount.get()

        then:
        tickedBeforeShutdown > 0

        when:
        shutdown.shutdown(null)
        Thread.sleep(20) // allow an in-flight tick, if any, to finish unwinding
        int tickCountAtStop = tickCount.get()
        Thread.sleep(100)

        then: 'no further ticks happened after shutdown() stopped the reaper'
        tickCount.get() == tickCountAtStop
    }
}
