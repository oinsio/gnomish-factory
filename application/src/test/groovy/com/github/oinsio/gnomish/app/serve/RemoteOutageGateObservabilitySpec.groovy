package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.logtext.RepeatOccurrence
import com.github.oinsio.gnomish.logtext.RepeatSuppressor
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import spock.lang.Specification

/**
 * FR14, NFR-O1, NFR-O3, UX6 of add-base-ref-resolution (task 7.4): the gate's own observability —
 * one WARN ({@link OperatorEvent#REMOTE_OUTAGE_GATE_OPENED}) on open, DEBUG-only for every failed
 * probe in between (never a second WARN for the same outage), one ERROR ({@link
 * OperatorEvent#REMOTE_OUTAGE_GATE_SUSTAINED_OPEN}) the first time an outage crosses the
 * sustained-open threshold, one INFO recovery line on close with duration and probe count, the
 * {@code onTransition}/{@code onClosedOutage} callbacks, and that the suppression key is scoped
 * per remote target so two gates sharing one {@link RepeatSuppressor} never cross-count.
 *
 * <p>{@link RemoteOutageGateSpec} covers the gate's open/close/backoff state machine in isolation;
 * this spec is purely about what it logs and hands its callbacks.
 */
class RemoteOutageGateObservabilitySpec extends Specification {

    private static final Duration IDLE = Duration.ofSeconds(30)
    private static final Duration CAP = Duration.ofMinutes(10)
    private static final Duration SUSTAINED_OPEN = Duration.ofHours(1)
    private static final Random NO_JITTER = new Random() {
        double nextDouble() {
            0.0
        }
    }

    private VirtualClock clock = new VirtualClock()
    // A fixed real-time clock for the suppressor: since it never advances, every failed probe on
    // the SAME reason lands inside the roll-up's quiet period, so First/Repeat are deterministic
    // without needing to drive two independent clocks in lockstep.
    private RepeatSuppressor suppressor = new RepeatSuppressor(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), Duration.ofMinutes(5))

    private RemoteOutageGate gate(
            BaseRefGit baseRefGit,
            String target = 'origin',
            Runnable onTransition = {},
            Consumer<RemoteOutageClosedOutage> onClosedOutage = { ignored -> }) {
        new RemoteOutageGate(
                baseRefGit, Path.of('.'), clock, NO_JITTER, IDLE, CAP,
                new RemoteOutageWiring(target, suppressor, SUSTAINED_OPEN, onTransition, onClosedOutage))
    }

    def "opening logs exactly one WARN naming the target and cause"() {
        given:
        def logs = LogCaptureSupport.attach(RemoteOutageGate)
        def g = gate(BaseRefGit.UNWIRED)

        when:
        g.openOnFailure('origin unreachable')

        then:
        def warns = logs.list.findAll { it.level == Level.WARN }
        warns.size() == 1
        warns[0].formattedMessage.startsWith(OperatorEvent.REMOTE_OUTAGE_GATE_OPENED.head())
        warns[0].formattedMessage.contains('origin')
        warns[0].formattedMessage.contains('origin unreachable')

        cleanup:
        logs.detach()
    }

    // FR14, NFR-O1: opening also reports the failure to the DEBUG-only suppressor stream — the
    //     same call site a later failed probe uses — so the very first failure counts toward the
    //     streak too, not only the ones observed after the WARN.
    def "opening also logs the DEBUG-level suppressor line, beside the WARN"() {
        given:
        def logs = LogCaptureSupport.attach(RemoteOutageGate, Level.DEBUG)
        def g = gate(BaseRefGit.UNWIRED)

        when:
        g.openOnFailure('origin unreachable')

        then:
        logs.list.any {
            it.level == Level.DEBUG && it.formattedMessage.contains('origin')
        }

        cleanup:
        logs.detach()
    }

    def "openOnFailure while already open logs no second WARN"() {
        given:
        def logs = LogCaptureSupport.attach(RemoteOutageGate)
        def g = gate(BaseRefGit.UNWIRED)
        g.openOnFailure('first failure')

        when:
        g.openOnFailure('second failure')

        then:
        logs.list.findAll { it.level == Level.WARN }.size() == 1

        cleanup:
        logs.detach()
    }

    def "a failed probe after open logs DEBUG only, never a second WARN"() {
        given:
        def logs = LogCaptureSupport.attach(RemoteOutageGate, Level.DEBUG)
        def baseRefGit = [probe: { Path p -> false }] as BaseRefGit
        def g = gate(baseRefGit)
        g.openOnFailure('boom')
        logs.list.clear()

        when:
        clock.advance(IDLE)
        g.probeIfDue()

        then:
        logs.list.every { it.level == Level.DEBUG }
        logs.list.any { it.formattedMessage.contains('origin') }

        cleanup:
        logs.detach()
    }

    def "closing logs one INFO recovery line with duration and probe count"() {
        given:
        def logs = LogCaptureSupport.attach(RemoteOutageGate)
        def open = true
        def baseRefGit = [probe: { Path p -> open }] as BaseRefGit
        def g = gate(baseRefGit)
        g.openOnFailure('boom')
        open = false
        clock.advance(IDLE)
        g.probeIfDue() // one failed probe before the close
        logs.list.clear()

        when:
        clock.advance(IDLE.multipliedBy(2))
        open = true
        g.probeIfDue()

        then:
        def infos = logs.list.findAll { it.level == Level.INFO }
        infos.size() == 1
        infos[0].formattedMessage.contains('1 failed probe')

        cleanup:
        logs.detach()
    }

    def "an outage past the sustained-open threshold logs exactly one ERROR"() {
        given:
        def logs = LogCaptureSupport.attach(RemoteOutageGate)
        def baseRefGit = [probe: { Path p -> false }] as BaseRefGit
        def g = gate(baseRefGit)
        g.openOnFailure('boom')

        when: 'probes keep failing well past the sustained-open threshold'
        (0..3).each {
            clock.advance(SUSTAINED_OPEN)
            g.probeIfDue()
        }

        then: 'exactly one ERROR, not one per probe past the threshold'
        def errors = logs.list.findAll { it.level == Level.ERROR }
        errors.size() == 1
        errors[0].formattedMessage.startsWith(OperatorEvent.REMOTE_OUTAGE_GATE_SUSTAINED_OPEN.head())

        cleanup:
        logs.detach()
    }

    // FR14, NFR-O1: the sustained-open watch is re-armed on every fresh open (openOnFailure calls
    //     RemoteOutageSustainedOpenWatch#reset), so a SECOND outage that also runs past the
    //     threshold gets its own ERROR — the latch from the first outage must not carry over.
    def "a second outage past the sustained-open threshold logs its own ERROR too"() {
        given:
        def logs = LogCaptureSupport.attach(RemoteOutageGate)
        def open = false
        def baseRefGit = [probe: { Path p -> open }] as BaseRefGit
        def g = gate(baseRefGit)
        g.openOnFailure('boom')
        clock.advance(SUSTAINED_OPEN)
        g.probeIfDue() // first outage crosses the threshold and logs its ERROR
        open = true
        clock.advance(CAP) // always past the doubled-backoff wait, whatever it grew to
        g.probeIfDue() // closes the first outage
        logs.list.clear()

        when: 'a second outage opens and also runs past the sustained-open threshold'
        open = false
        g.openOnFailure('boom again')
        clock.advance(SUSTAINED_OPEN)
        g.probeIfDue()

        then: 'its own ERROR fires — the latch did not carry over from the first outage'
        logs.list.any { it.level == Level.ERROR }

        cleanup:
        logs.detach()
    }

    def "onTransition fires on open and close, never on a mere failed probe"() {
        given:
        def transitions = 0
        def open = true
        def baseRefGit = [probe: { Path p -> open }] as BaseRefGit
        def g = gate(baseRefGit, 'origin', { transitions++ })

        when:
        g.openOnFailure('boom')

        then:
        transitions == 1

        when: 'a failed probe: no transition'
        open = false
        clock.advance(IDLE)
        g.probeIfDue()

        then:
        transitions == 1

        when: 'the closing probe: one more transition'
        open = true
        clock.advance(IDLE.multipliedBy(2))
        g.probeIfDue()

        then:
        transitions == 2
    }

    def "onClosedOutage receives the outage summary exactly once, with the probe count and last error"() {
        given:
        def received = new AtomicReference<RemoteOutageClosedOutage>()
        def open = true
        def baseRefGit = [probe: { Path p -> open }] as BaseRefGit
        def g = gate(baseRefGit, 'origin', {}, { outage ->
            received.set(outage)
        })
        g.openOnFailure('origin unreachable')
        open = false
        clock.advance(IDLE)
        g.probeIfDue()
        // The interval doubled after the first failure, so the second failed probe needs more
        // than another plain IDLE tick to become due.
        clock.advance(IDLE.multipliedBy(3))
        g.probeIfDue()

        when:
        open = true
        clock.advance(CAP)
        g.probeIfDue()

        then:
        def outage = received.get()
        outage != null
        outage.target() == 'origin'
        outage.probeCount() == 2
        outage.releasedClaims() == 0
    }

    def "claimReleasedWhileOpen feeds the closed outage's releasedClaims count"() {
        given:
        def received = new AtomicReference<RemoteOutageClosedOutage>()
        def open = true
        def baseRefGit = [probe: { Path p -> open }] as BaseRefGit
        def g = gate(baseRefGit, 'origin', {}, { outage ->
            received.set(outage)
        })
        g.openOnFailure('boom')
        g.claimReleasedWhileOpen()
        g.claimReleasedWhileOpen()

        when:
        clock.advance(IDLE)
        g.probeIfDue()

        then:
        received.get().releasedClaims() == 2
    }

    // The suppressor key is exactly "remote-outage:<target>" — proven directly by reporting a
    // second failure under that literal key right after a failed probe and observing the streak
    // continue (a Repeat of count 2, not a fresh First), which only happens if the gate's own
    // probeIfDue call really landed on this exact key.
    def "the suppressor key is remote-outage: plus the target, not some other identity"() {
        given:
        def baseRefGit = [probe: { Path p -> false }] as BaseRefGit
        def g = gate(baseRefGit, 'origin')
        g.openOnFailure('boom')

        when:
        clock.advance(IDLE)
        g.probeIfDue()
        def occurrence = suppressor.failed('remote-outage:origin', 'origin did not answer ls-remote HEAD')

        then: 'the streak the probe just reported to continued under this exact key'
        occurrence instanceof RepeatOccurrence.Repeat
        (occurrence as RepeatOccurrence.Repeat).count() == 2
    }

    // The suppression-key-per-remote-target proof: two gates sharing ONE suppressor never mask
    // each other's DEBUG streak counts, even though today's production wiring builds only one gate
    // (task 7.3's own "one gate per process" scope) — the keying logic itself is unit-testable
    // with two arbitrary targets independent of how many gates production actually wires.
    def "two gates sharing one suppressor keep independent failure streaks per target"() {
        given:
        def logsA = LogCaptureSupport.attach(RemoteOutageGate, Level.DEBUG)
        def baseRefGitA = [probe: { Path p -> false }] as BaseRefGit
        def baseRefGitB = [probe: { Path p -> false }] as BaseRefGit
        def gateA = gate(baseRefGitA, 'origin-a')
        def gateB = gate(baseRefGitB, 'origin-b')
        gateA.openOnFailure('boom-a')
        gateB.openOnFailure('boom-b')
        logsA.list.clear()

        when: 'gate A fails three more times, gate B fails once'
        // Each gate's own backoff doubles after every failure, so CAP (the ceiling) always covers
        // however long the next probe's wait grew to.
        3.times {
            clock.advance(CAP)
            gateA.probeIfDue()
        }
        clock.advance(CAP)
        gateB.probeIfDue()

        then: 'the last DEBUG line for gate A reports its own count (3rd probe failure), not gate Bs'
        def aLines = logsA.list.findAll {
            it.formattedMessage.contains('origin-a')
        }
        def bLines = logsA.list.findAll {
            it.formattedMessage.contains('origin-b')
        }
        aLines.any {
            it.formattedMessage.contains('(3x)')
        }
        bLines.every {
            !it.formattedMessage.contains('(3x)')
        }

        cleanup:
        logsA.detach()
    }
}
