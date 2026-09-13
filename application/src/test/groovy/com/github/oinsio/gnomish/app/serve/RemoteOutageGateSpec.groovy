package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification

/**
 * FR14, NFR-R3, D9 of add-base-ref-resolution (task 7.3): the remote outage gate's own mechanics,
 * on virtual time — a fresh gate starts closed; it opens on {@link RemoteOutageGate#openOnFailure},
 * probes on a jittered interval that grows from the idle interval to a configured cap ({@link
 * com.github.oinsio.gnomish.app.lease.RestartBackoff}'s policy, reused rather than forked), closes
 * on the first successful probe, and resets its interval to the idle floor ONLY on the first
 * successful base refresh observed after that close — never on the probe itself, so a flapping
 * remote (probe passes, refresh fails) reopens at a longer interval, never at the idle one.
 *
 * <p>{@link FeedCycle}'s own consultation of the gate (zero tracker claim calls while open) is
 * {@code FeedCycleSpec}'s job; this spec drives the gate in isolation.
 */
class RemoteOutageGateSpec extends Specification {

    private static final Duration IDLE = Duration.ofSeconds(30)
    private static final Duration CAP = Duration.ofMinutes(10)
    private static final Random NO_JITTER = new Random() {
        double nextDouble() {
            0.0
        }
    }

    private VirtualClock clock = new VirtualClock()

    private RemoteOutageGate gate(BaseRefGit baseRefGit, Random random = NO_JITTER) {
        new RemoteOutageGate(baseRefGit, Path.of('.'), clock, random, IDLE, CAP)
    }

    // FR14: "the gate is process-local: a restart forgets it" — a freshly constructed gate is
    //     closed, i.e. claims are allowed, with no prior failure observed.
    def "a fresh gate starts closed"() {
        expect:
        !gate(BaseRefGit.UNWIRED).isOpen()
    }

    // FR14, NFR-O1, NFR-O3 of add-base-ref-resolution: the composition-root factory (task 7.4)
    //     wires a real, usable, closed gate under the target DEFAULT_TARGET names.
    def "the composition-root system() factory builds a real, closed gate"() {
        given:
        // real-time-wiring: the composition-root factory IS the subject of this feature — the gate is
        //     built and read, never opened or probed, so no clock-driven decision runs.
        def g = RemoteOutageGates.system(BaseRefGit.UNWIRED, Path.of('.'), Duration.ofSeconds(30),
                Duration.ofMinutes(10), Duration.ofHours(1), {}, { ignored -> })

        expect:
        !g.isOpen()
        g.health().target() == 'origin'
    }

    // FR14: a slot's infrastructure failure opens the gate.
    def "openOnFailure opens the gate"() {
        given:
        def g = gate(BaseRefGit.UNWIRED)

        when:
        g.openOnFailure("boom")

        then:
        g.isOpen()
    }

    // FR14, NFR-O1: opening resets the outage's own failure count to one, even carried across a
    //     prior outage that left it non-zero — RemoteOutageGate delegates this to
    //     RemoteOutageCounters#opened() rather than tracking it itself.
    def "openOnFailure resets the health's consecutive-failure count to one"() {
        given:
        def g = gate([probe: { Path p -> false }] as BaseRefGit)

        when:
        g.openOnFailure("boom")

        then:
        g.health().consecutiveFailures() == 1
    }

    // NFR-O1: health reports the next scheduled probe instant only while open; once closed there
    //     is no scheduled probe to report.
    def "health reports the next probe instant while open, and none once closed"() {
        given:
        def open = true
        def g = gate([probe: { Path p -> open }] as BaseRefGit)
        g.openOnFailure("boom")

        expect: 'a scheduled probe instant while open'
        g.health().nextProbeAt() != null

        when: 'the next probe succeeds and closes the gate'
        clock.advance(IDLE)
        open = false
        g.probeIfDue()

        then: 'still open (that probe failed): still reports a schedule'
        g.health().nextProbeAt() != null

        when:
        clock.advance(IDLE.multipliedBy(2))
        open = true
        g.probeIfDue()

        then: 'closed: no scheduled probe'
        !g.isOpen()
        g.health().nextProbeAt() == null
    }

    // FR14, NFR-O1: closing clears the consecutive-failure count back to zero (RemoteOutageCounters#closed()).
    def "closing resets the health's consecutive-failure count to zero"() {
        given:
        def open = true
        def g = gate([probe: { Path p -> open }] as BaseRefGit)
        g.openOnFailure("boom")
        clock.advance(IDLE)
        open = false
        g.probeIfDue()
        g.health().consecutiveFailures() > 0

        when:
        clock.advance(IDLE.multipliedBy(2))
        open = true
        g.probeIfDue()

        then:
        g.health().consecutiveFailures() == 0
    }

    // Design D9: a second failure while already open does not disturb the schedule — probeIfDue
    //     right after a repeated openOnFailure() must still be "not yet due" (idle interval unspent).
    def "openOnFailure is idempotent while already open"() {
        given:
        def probes = new AtomicInteger()
        def baseRefGit = [probe: { Path p ->
                probes.incrementAndGet(); true
            }] as BaseRefGit
        def g = gate(baseRefGit)
        g.openOnFailure("boom")

        when: 'a second failure arrives before the first probe would even be due'
        g.openOnFailure("boom")
        g.probeIfDue()

        then: 'still due at the ORIGINAL idle interval, not restarted from a later openOnFailure call'
        clock.advance(IDLE)
        g.probeIfDue()
        probes.get() == 1
    }

    // FR14: while open, the daemon probes on a jittered interval growing from the idle interval;
    //     the first probe is scheduled at the idle interval — probeIfDue is a no-op before it elapses.
    def "probeIfDue does not probe before the scheduled instant"() {
        given:
        def probes = new AtomicInteger()
        def baseRefGit = [probe: { Path p ->
                probes.incrementAndGet(); false
            }] as BaseRefGit
        def g = gate(baseRefGit)
        g.openOnFailure("boom")

        when:
        clock.advance(IDLE.minusSeconds(1))
        g.probeIfDue()

        then:
        probes.get() == 0
        g.isOpen()
    }

    // FR14: the first successful probe closes the gate.
    def "the first successful probe closes the gate"() {
        given:
        def baseRefGit = [probe: { Path p -> true }] as BaseRefGit
        def g = gate(baseRefGit)
        g.openOnFailure("boom")

        when:
        clock.advance(IDLE)
        g.probeIfDue()

        then:
        !g.isOpen()
    }

    // FR14: a failed probe re-arms the gate — it stays open and the next interval grows (doubles),
    //     verified by requiring the SECOND probe to wait the doubled interval, not the idle one again.
    def "a failed probe re-arms the gate for a longer interval"() {
        given:
        def probes = new AtomicInteger()
        def baseRefGit = [probe: { Path p ->
                probes.incrementAndGet(); false
            }] as BaseRefGit
        def g = gate(baseRefGit)
        g.openOnFailure("boom")

        when: 'the first scheduled probe fails'
        clock.advance(IDLE)
        g.probeIfDue()

        then:
        g.isOpen()
        probes.get() == 1

        when: 'only the (unchanged) idle interval elapses again — not yet enough for the doubled wait'
        clock.advance(IDLE)
        g.probeIfDue()

        then: 'no second probe yet: the re-armed interval is longer than the idle one'
        probes.get() == 1

        when: 'the rest of the doubled interval elapses'
        clock.advance(IDLE)
        g.probeIfDue()

        then:
        probes.get() == 2
    }

    // FR14: recovery is confirmed by a probe, never by claiming a task — onSuccessfulRefresh alone
    //     never closes the gate.
    def "onSuccessfulRefresh alone never closes the gate"() {
        given:
        def g = gate(BaseRefGit.UNWIRED)
        g.openOnFailure("boom")

        when:
        g.onSuccessfulRefresh()

        then:
        g.isOpen()
    }

    // FR14, the crux: closing the gate does NOT reset the probe interval by itself — only the
    //     first successful base refresh AFTER the close does. Proven by reopening without ever
    //     calling onSuccessfulRefresh and observing the SAME (already-grown) interval is required,
    //     not the idle one.
    def "closing the gate does not reset the interval; a reopen without a successful refresh keeps the grown interval"() {
        given:
        def open = true
        def baseRefGit = [probe: { Path p -> open }] as BaseRefGit
        def g = gate(baseRefGit)
        g.openOnFailure("boom")

        when: 'the probe fails once, growing the interval past idle'
        clock.advance(IDLE)
        open = false
        g.probeIfDue()

        then:
        g.isOpen()

        when: 'the (now doubled) interval elapses and the probe succeeds, closing the gate'
        clock.advance(IDLE.multipliedBy(2))
        open = true
        g.probeIfDue()

        then: 'closed, but NO refresh ever succeeded after the close'
        !g.isOpen()

        when: 'a new failure reopens the gate (no onSuccessfulRefresh happened in between)'
        g.openOnFailure("boom")
        clock.advance(IDLE)
        open = false
        g.probeIfDue()

        then: 'still open at the idle interval alone: the reset never armed a fresh idle-length probe'
        g.isOpen()
    }

    // FR14: the flapping-remote scenario from the spec's own class doc, end to end — close, an
    //     immediate refresh failure (no onSuccessfulRefresh call), reopen with a LONGER interval
    //     than the idle one: the probe right after the reopen does not fire at +idle alone, only
    //     once the doubled interval elapses.
    def "flapping remote: close, refresh fails, reopen requires more than the idle interval to probe again"() {
        given:
        def probes = new AtomicInteger()
        def answer = true
        def baseRefGit = [probe: { Path p ->
                probes.incrementAndGet(); answer
            }] as BaseRefGit
        def g = gate(baseRefGit)
        g.openOnFailure("boom")

        when: 'the first probe (at idle) succeeds and closes the gate'
        clock.advance(IDLE)
        g.probeIfDue()

        then:
        !g.isOpen()
        probes.get() == 1

        when: 'the base refresh that follows the close fails (never calls onSuccessfulRefresh), reopening the gate'
        g.openOnFailure("boom")
        answer = false

        and: 'only the idle interval elapses'
        clock.advance(IDLE)
        g.probeIfDue()

        then: 'no second probe yet: the schedule grew past the idle interval'
        probes.get() == 1
        g.isOpen()

        when: 'the doubled interval elapses'
        clock.advance(IDLE)
        g.probeIfDue()

        then: 'the probe now fires, at the doubled interval, never the plain idle one'
        probes.get() == 2
    }

    // FR14: the first successful refresh after a close resets the interval — proven by requiring
    //     only the idle interval (not a grown one) before the NEXT probe after a subsequent reopen.
    def "the first successful refresh after a close resets the interval to idle"() {
        given:
        def probes = new AtomicInteger()
        def answer = true
        def baseRefGit = [probe: { Path p ->
                probes.incrementAndGet(); answer
            }] as BaseRefGit
        def g = gate(baseRefGit)
        g.openOnFailure("boom")

        when: 'the probe fails once, growing the interval'
        clock.advance(IDLE)
        answer = false
        g.probeIfDue()

        then:
        g.isOpen()
        probes.get() == 1

        when: 'the grown (doubled) interval elapses and the probe succeeds, closing the gate'
        clock.advance(IDLE.multipliedBy(2))
        answer = true
        g.probeIfDue()

        then:
        !g.isOpen()
        probes.get() == 2

        when: 'the base refresh that follows the close SUCCEEDS this time'
        g.onSuccessfulRefresh()

        and: 'a new failure reopens the gate and exactly the idle interval elapses'
        g.openOnFailure("boom")
        answer = false
        clock.advance(IDLE)
        g.probeIfDue()

        then: 'the reset took effect: a probe fires again at the plain idle interval'
        probes.get() == 3
    }

    // FR14: the interval resets "only after the first successful base refresh that FOLLOWS the
    //     close". A slot that started before the outage and finishes while the gate is still open
    //     reports a refresh that predates the open — it must not shrink the growing probe schedule
    //     mid-outage: the next probe after a second failure still waits the quadrupled interval.
    def "a successful refresh reported while the gate is open does not reset the probe interval"() {
        given:
        def probes = new AtomicInteger()
        def baseRefGit = [probe: { Path p ->
                probes.incrementAndGet(); false
            }] as BaseRefGit
        def g = gate(baseRefGit)
        g.openOnFailure("boom")

        when: 'the first probe fails, growing the interval to 2x idle'
        clock.advance(IDLE)
        g.probeIfDue()

        and: 'an in-flight slot that fetched its base BEFORE the outage finishes now'
        g.onSuccessfulRefresh()

        and: 'the second probe fails at the doubled interval, growing it to 4x idle'
        clock.advance(IDLE.multipliedBy(2))
        g.probeIfDue()

        then:
        g.isOpen()
        probes.get() == 2

        when: 'only the doubled interval elapses — not yet the quadrupled one'
        clock.advance(IDLE.multipliedBy(2))
        g.probeIfDue()

        then: 'no third probe yet: the stale refresh signal did not shrink the schedule'
        probes.get() == 2
    }

    // FR14: the pending reset is consumed only by a refresh after the close — a stale refresh
    //     signal during the outage must not spend it, so the genuine post-close refresh still
    //     resets the interval to idle for the next outage.
    def "a refresh reported while open does not consume the post-close reset"() {
        given:
        def probes = new AtomicInteger()
        def answer = false
        def baseRefGit = [probe: { Path p ->
                probes.incrementAndGet(); answer
            }] as BaseRefGit
        def g = gate(baseRefGit)
        g.openOnFailure("boom")

        when: 'the first probe fails, then an in-flight slot reports a (pre-outage) refresh'
        clock.advance(IDLE)
        g.probeIfDue()
        g.onSuccessfulRefresh()

        and: 'the second probe fails too, then the third succeeds and closes the gate'
        clock.advance(IDLE.multipliedBy(2))
        g.probeIfDue()
        clock.advance(IDLE.multipliedBy(4))
        answer = true
        g.probeIfDue()

        then:
        !g.isOpen()
        probes.get() == 3

        when: 'the base refresh that follows the close succeeds, then a new failure reopens the gate'
        g.onSuccessfulRefresh()
        g.openOnFailure("boom")
        answer = false
        clock.advance(IDLE)
        g.probeIfDue()

        then: 'the reset took effect: the probe fires at the plain idle interval'
        probes.get() == 4
    }
}
