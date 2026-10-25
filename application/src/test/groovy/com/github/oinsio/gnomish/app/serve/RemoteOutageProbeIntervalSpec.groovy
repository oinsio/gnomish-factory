package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification

/**
 * FR14, NFR-R3, D9 of add-base-ref-resolution: the probe schedule of an open remote outage gate
 * seen across a long outage on virtual time — every probe fails, and the gaps between them are
 * read off the gate's own health ({@code nextProbeAt}), with real jitter from a seeded random.
 */
class RemoteOutageProbeIntervalSpec extends Specification {

    private static final Duration IDLE = Duration.ofSeconds(30)
    private static final Duration CAP = Duration.ofMinutes(10)
    /** The bottom of the 20% jitter band below the cap, where jitter, not growth, orders the gaps. */
    private static final Duration JITTER_BAND_FLOOR = Duration.ofMinutes(8)

    private final VirtualClock clock = new VirtualClock()

    /** The gaps between {@code count} consecutive failed probes, the first measured from the open. */
    private List<Duration> gapsBetweenFailedProbes(int count) {
        def failing = [probe: { Path p -> false }] as BaseRefGit
        def gate = new DaemonCondition(ConditionSubject.remote(failing, Path.of('.')), clock, new Random(42), IDLE, CAP)
        gate.openOnFailure('remote unreachable')
        def gaps = []
        def last = clock.instant()
        count.times {
            def due = gate.health().nextProbeAt()
            gaps << Duration.between(last, due)
            clock.advance(Duration.between(clock.instant(), due))
            last = clock.instant()
            gate.probeIfDue()
            assert gate.isOpen()
        }
        gaps
    }

    // F95: the jittered interval is clamped to the cap after the jitter; at the cap the jitter is
    // taken off the cap, so the gaps there stay distinct inside the jitter band below it.
    // Scenario: serve/factory-serve / Probe intervals grow with jitter and stop at the cap
    def "FR14: eight failed probes on virtual time space out from the idle interval toward the cap, jittered, never past the cap"() {
        when:
        def gaps = gapsBetweenFailedProbes(8)

        then: 'they start at the idle interval and grow toward the cap'
        gaps.first() >= IDLE
        gaps.last() > gaps.first()
        (0..<gaps.size() - 1).every {
            gaps[it + 1] >= gaps[it] || gaps[it] >= JITTER_BAND_FLOOR
        }

        and: 'jitter makes every gap distinct'
        gaps.toSet().size() == gaps.size()

        and: 'and none exceeds the cap'
        gaps.every { it <= CAP }
    }
}
