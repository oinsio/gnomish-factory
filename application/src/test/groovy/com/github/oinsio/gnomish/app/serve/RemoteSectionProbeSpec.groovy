package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerHealthTracker
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.serveobservability.RemoteHealth
import com.github.oinsio.gnomish.serveobservability.RemoteHealthAssembler
import com.github.oinsio.gnomish.serveobservability.TrackerHealthAssembler
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification

/**
 * The snapshot's {@code remote} section while the gate probes a dead remote (NFR-O3, UX6 of
 * add-base-ref-resolution), read through the same assemblers {@code SnapshotSources} writes the
 * snapshot from: a real {@link RemoteOutageGate} on virtual time whose probe keeps failing, beside
 * the daemon's {@link TrackerHealthTracker}, which the probes never touch.
 *
 * <p>Implements NFR-O3, UX6 of add-base-ref-resolution.
 */
class RemoteSectionProbeSpec extends Specification {

    private static final Duration IDLE = Duration.ofSeconds(30)
    private static final Duration CAP = Duration.ofMinutes(10)
    private static final Random NO_JITTER = new Random() {
        double nextDouble() {
            0.0
        }
    }

    private final VirtualClock clock = new VirtualClock()
    private final Tracker delegate = Mock()
    private final TrackerHealthTracker trackerHealth = new TrackerHealthTracker(delegate, clock)
    private final RemoteOutageGate gate =
    new RemoteOutageGate([probe: { Path p ->
            false
        }] as BaseRefGit, Path.of('.'), clock, NO_JITTER, IDLE, CAP)

    private RemoteHealth remoteSection() {
        RemoteHealthAssembler.assemble([gate])['origin']
    }

    /** Lets each scheduled probe come due, runs it, and returns the section after every probe. */
    private List<RemoteHealth> threeFailedProbes() {
        (1..3).collect {
            clock.advance(CAP)
            gate.probeIfDue()
            remoteSection()
        }
    }

    // Scenario: observability/serve-observability / Probes advance the section without claims
    def "NFR-O3: failed probes move nextProbeAt out, count each failure, keep lastSuccessAt, and leave the tracker section alone"() {
        given: 'the tracker was last read successfully, then a slot met the dead remote and opened the gate'
        trackerHealth.listReady(10)
        def trackerSection = TrackerHealthAssembler.assemble(trackerHealth)
        gate.openOnFailure('origin unreachable')
        def opened = remoteSection()

        when:
        def sections = threeFailedProbes()

        then: 'no tracker call was made by any probe'
        0 * delegate._

        and: 'each failure moves the next probe out and adds one to the failure count'
        ([opened] + sections).collect {
            it.nextProbeAt()
        }.collate(2, 1, false).every { a, b ->
            b.isAfter(a)
        }
        sections*.consecutiveFailures() == (1..3).collect {
            opened.consecutiveFailures() + it
        }

        and: 'the gate stays open and lastSuccessAt never advances'
        sections.every {
            it.open() && it.lastSuccessAt() == opened.lastSuccessAt()
        }

        and: 'the tracker section is exactly what it was'
        TrackerHealthAssembler.assemble(trackerHealth) == trackerSection
    }

    // F91: the gate counts the failed fetch that opened it, so three failed probes read four.
    // F91: scenario text aligned to what this test asserts
    // Scenario: observability/serve-observability / Probes advance the section without claims
    def "NFR-O3: three failed probes while the gate is open read as four consecutive failures, the opening failure included"() {
        given:
        gate.openOnFailure('origin unreachable')

        expect:
        threeFailedProbes().last().consecutiveFailures() == 4
    }
}
