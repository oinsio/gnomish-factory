package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.time.Duration
import spock.lang.Specification

/**
 * LeaseThresholds: the holder's lost-detection deadline and the reaper's reassignment deadline,
 * derived from one config so the order between them cannot drift (claim-heartbeat "Lost-detection
 * strictly precedes reassignment"; FR13 of harden-task-branch-contract).
 */
class LeaseThresholdsSpec extends Specification {

    private static TrackerConfig config(Duration interval, int multiplier) {
        new TrackerConfig('inmemory', 3, interval, multiplier, [:])
    }

    // FR13: the holder stops writing strictly before any reaper could hand the task to another
    //     instance — for every interval and every multiplier the config rule admits
    def "lost detection is strictly earlier than reassignment (interval #interval, multiplier #multiplier)"() {
        given:
        def resolved = config(interval, multiplier)

        expect:
        LeaseThresholds.lostDetection(resolved) <LeaseThresholds.reassignment(resolved)

        where:
        interval | multiplier
        Duration.ofMinutes(5) | 3
        Duration.ofMinutes(5) | 4
        Duration.ofSeconds(30) | 3
        Duration.ofSeconds(1) | 10
    }

    // FR13: the grace window is a whole beat interval wide — long enough for a recovering holder to
    //     land one beat, which is what lets it keep a claim it never actually lost
    def "the grace window between the two deadlines is one whole beat interval"() {
        given:
        def resolved = config(Duration.ofMinutes(5), 3)

        expect:
        LeaseThresholds.reassignment(resolved) - LeaseThresholds.lostDetection(resolved) == Duration.ofMinutes(5)
    }

    // FR5 of add-claim-heartbeat: reassignment stays exactly multiplier × interval, the TTL the
    //     staleness memory has always measured
    def "reassignment is the TTL the reaper measures"() {
        expect:
        LeaseThresholds.reassignment(config(Duration.ofMinutes(5), 3)) == Duration.ofMinutes(15)
    }

    // FR19, FR12 of harden-task-branch-contract: a frozen write-sequence window gets one whole beat
    //     interval MORE than a merely silent holder — rolling back a sequence another instance is
    //     still completing would cost a race no fence arbitrates, while waiting costs only latency
    //     on a task nobody is working.
    def "the window grace is one whole interval longer than reassignment"() {
        given:
        def resolved = config(Duration.ofMinutes(5), 3)

        expect:
        LeaseThresholds.windowGrace(resolved) == Duration.ofMinutes(20)
        LeaseThresholds.windowGrace(resolved) - LeaseThresholds.reassignment(resolved) == Duration.ofMinutes(5)
        LeaseThresholds.windowGrace(resolved) > LeaseThresholds.reassignment(resolved)
    }
}
