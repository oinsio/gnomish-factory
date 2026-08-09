package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.engine.SystemClock
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.lease.ReaperDuty
import com.github.oinsio.gnomish.app.lease.StandingReaper
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.serve.ServeShutdown
import com.github.oinsio.gnomish.app.serve.SlotLedger
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.time.Duration
import java.util.function.Supplier
import spock.lang.Specification

/**
 * {@link ServeAssembly#shutdown}: the per-invocation {@link ServeShutdown} factory (FR11, D9 of
 * add-factory-serve) — proves the returned instance is a genuine, functional wiring over the
 * SAME {@code slotLedger}/{@code claimLossFlag} passed in, not merely a non-null object. Other
 * {@link ServeAssembly} factory methods are exercised through {@code ServeCommand}'s own
 * assembly wiring; this spec is scoped to {@code shutdown} alone.
 *
 * Implements FR11, D9 of add-factory-serve.
 */
class ServeAssemblySpec extends Specification {

    private static final TaskRef REF = new TaskRef('github:o/r#1')

    // FR11, D9: the factory must actually return a usable ServeShutdown — not null — wired over
    // the caller's own SlotLedger and ClaimLossFlag, so that running it flags an occupied slot's
    // claim in that SAME ClaimLossFlag instance (the round-boundary check elsewhere consults).
    def "builds a ServeShutdown wired over the given slot ledger and claim-loss flag"() {
        given:
        def slotLedger = new SlotLedger(1)
        slotLedger.acquire()
        slotLedger.assign(REF)
        def claimLossFlag = new ClaimLossFlag()
        def serveProperties = new ServeProperties(
                1, Duration.ofSeconds(30), Duration.ofMillis(50), Duration.ofDays(14), null, null)
        def standingReaper = new StandingReaper(
                ReaperDuty.NONE, { Duration d -> } as Sleeper, Duration.ofSeconds(30), { [] } as Supplier, new SystemClock())

        when:
        def shutdown = ServeAssembly.shutdown(slotLedger, claimLossFlag, serveProperties, standingReaper)

        then: 'a genuine, non-null ServeShutdown is returned, wired over the SAME standing reaper'
        shutdown != null
        shutdown instanceof ServeShutdown
        shutdown.standingReaper().is(standingReaper)

        when: 'running it'
        shutdown.shutdown(null)

        then: 'it acted on the SAME slotLedger/claimLossFlag this invocation shared with it — not a stub'
        claimLossFlag.isLost(REF)
        claimLossFlag.reason(REF) == ServeShutdown.SHUTDOWN_REASON

        cleanup:
        slotLedger.release(REF)
    }
}
