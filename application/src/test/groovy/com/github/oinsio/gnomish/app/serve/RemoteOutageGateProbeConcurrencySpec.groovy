package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification

/**
 * FR14, NFR-R3 of add-base-ref-resolution: what the gate's monitor may and may not be held across.
 * {@link RemoteOutageGate#probeIfDue} runs a {@code git ls-remote} subprocess bounded by {@code
 * factory.git-network-timeout} (five minutes by default), so it runs with the monitor released
 * (CERT LCK09-J) — otherwise a dead remote stalls every slot whose base read reports back through
 * {@link RemoteOutageGate#openOnFailure}/{@link RemoteOutageGate#onSuccessfulRefresh}, both of
 * which are pure no-ops while the gate is open.
 *
 * <p>Real threads and a probe that blocks on a latch, since the property under test is exactly the
 * one a single-threaded spec cannot see. {@code RemoteOutageGateSpec} owns the gate's mechanics on
 * virtual time; this spec owns only the lock discipline around the probe.
 */
class RemoteOutageGateProbeConcurrencySpec extends Specification {

    private static final Duration IDLE = Duration.ofSeconds(30)
    private static final Duration CAP = Duration.ofMinutes(10)
    private static final Duration PROMPTLY = Duration.ofSeconds(2)
    private static final Duration GENEROUSLY = Duration.ofSeconds(30)
    private static final Random NO_JITTER = new Random() {
        double nextDouble() {
            0.0
        }
    }

    private VirtualClock clock = new VirtualClock()
    private CountDownLatch probeEntered = new CountDownLatch(1)
    private CountDownLatch releaseProbe = new CountDownLatch(1)
    private AtomicInteger probes = new AtomicInteger()

    /** A probe standing in for a wedged ls-remote: it blocks until the spec releases it. */
    private BaseRefGit blockingProbe(boolean answer = false) {
        [probe: { Path p ->
                probes.incrementAndGet()
                probeEntered.countDown()
                releaseProbe.await(GENEROUSLY.toSeconds(), TimeUnit.SECONDS)
                answer
            }] as BaseRefGit
    }

    private RemoteOutageGate openGateDueForProbe(BaseRefGit baseRefGit) {
        def gate = new RemoteOutageGate(baseRefGit, Path.of('.'), clock, NO_JITTER, IDLE, CAP)
        gate.openOnFailure('origin is unreachable')
        clock.advance(IDLE)
        gate
    }

    // FR14: a slot's signal is a no-op while the gate is open, so it must not wait out a probe
    //     subprocess that can run for the whole git network timeout.
    def "a slot's signal does not wait for an in-flight probe"() {
        given:
        def gate = openGateDueForProbe(blockingProbe())

        when: 'a probe is in flight'
        def prober = Thread.ofPlatform().start { gate.probeIfDue() }
        probeEntered.await(GENEROUSLY.toSeconds(), TimeUnit.SECONDS)

        and: 'two slot threads report their base reads'
        def signalled = new CountDownLatch(2)
        Thread.ofPlatform().start {
            gate.onSuccessfulRefresh()
            signalled.countDown()
        }
        Thread.ofPlatform().start {
            gate.openOnFailure('a second slot saw it too')
            signalled.countDown()
        }

        then: 'both return without waiting for the subprocess'
        signalled.await(PROMPTLY.toSeconds(), TimeUnit.SECONDS)

        cleanup:
        releaseProbe.countDown()
        prober.join()
    }

    // FR14: the health read backs the snapshot's `remote` section, written by the snapshot thread —
    //     it must stay readable while the remote is wedged, not freeze for the probe's duration.
    def "health stays readable while a probe is in flight"() {
        given:
        def gate = openGateDueForProbe(blockingProbe())

        when:
        def prober = Thread.ofPlatform().start { gate.probeIfDue() }
        probeEntered.await(GENEROUSLY.toSeconds(), TimeUnit.SECONDS)

        def read = new CountDownLatch(1)
        Thread.ofPlatform().start {
            gate.health()
            gate.isOpen()
            read.countDown()
        }

        then:
        read.await(PROMPTLY.toSeconds(), TimeUnit.SECONDS)

        cleanup:
        releaseProbe.countDown()
        prober.join()
    }

    // FR14: the monitor no longer serializes the probe itself, so the in-flight latch is what keeps
    //     one probe per due instant — a second driver must leave rather than spawn a second
    //     ls-remote and double-count against the schedule.
    def "a second caller does not start a probe while one is in flight"() {
        given:
        def gate = openGateDueForProbe(blockingProbe())

        when: 'the first probe is in flight and a second caller arrives at the same due instant'
        def prober = Thread.ofPlatform().start { gate.probeIfDue() }
        probeEntered.await(GENEROUSLY.toSeconds(), TimeUnit.SECONDS)
        gate.probeIfDue()

        then: 'only the in-flight one ran'
        probes.get() == 1

        cleanup:
        releaseProbe.countDown()
        prober.join()
    }

    // FR14: a probe that cannot run at all (no git binary) must release the latch on its way out —
    //     a leaked latch would leave the gate unable to probe, and so unable to close, for the
    //     rest of the daemon's life.
    def "a probe that throws leaves the gate able to probe again"() {
        given:
        def failFirst = true
        def gate = openGateDueForProbe([probe: { Path p ->
                if (failFirst) {
                    failFirst = false
                    throw new IllegalStateException('git binary not found')
                }
                true
            }] as BaseRefGit)

        when:
        gate.probeIfDue()

        then:
        thrown(IllegalStateException)
        gate.isOpen()

        when: 'the next due probe finds origin answering'
        gate.probeIfDue()

        then:
        !gate.isOpen()
    }
}
