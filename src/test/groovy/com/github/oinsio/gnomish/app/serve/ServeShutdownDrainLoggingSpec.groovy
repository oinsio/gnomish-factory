package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import java.time.Duration

/**
 * ServeShutdown grace-window summary line (FR11, D9): awaitDrainedQuietly's boolean result — and the
 * "any slot occupied" branch guarding it — are only observable through the summary log line, so
 * these scenarios assert on it directly (true when the slot drains in time, false when the grace
 * window expires, absent when nothing was occupied). Shared fixtures live in
 * {@link ServeShutdownSpecBase}.
 *
 * Implements FR11, D9 of add-factory-serve.
 */
class ServeShutdownDrainLoggingSpec extends ServeShutdownSpecBase {

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
        def shutdown = new ServeShutdown(ledger, flag, grace, killer, inertReaper())

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
        def shutdown = new ServeShutdown(ledger, flag, Duration.ofMillis(50), killer, inertReaper())

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

    // FR11: line 90's guard (`if (!occupied.isEmpty())`) must actually matter — with nothing
    //     occupied at SIGTERM, no grace-window summary should be logged at all. A negated guard
    //     would flip this and log the (vacuous) summary instead.
    def "logs no grace-window summary when nothing was occupied at shutdown"() {
        given:
        def ledger = new SlotLedger(2)
        def flag = new ClaimLossFlag()
        def killer = new RecordingKiller()
        def shutdown = new ServeShutdown(ledger, flag, Duration.ofMillis(50), killer, inertReaper())

        when:
        List<ILoggingEvent> events = capture { shutdown.shutdown(null) }

        then:
        events.every { !it.formattedMessage.contains('in-flight task') }
    }
}
