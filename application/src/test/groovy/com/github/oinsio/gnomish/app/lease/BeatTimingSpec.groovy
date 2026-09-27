package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.time.Duration
import spock.lang.Specification

/**
 * BeatTiming (design D12 of collapse-composition-roots): the beat interval and the lost-detection
 * threshold travel as one value, and the value owns the order between them.
 */
class BeatTimingSpec extends Specification {

    // FR1 of collapse-composition-roots: a holder cannot judge a claim unconfirmed before one beat
    //     has had time to land, so a threshold shorter than the interval is refused at construction
    def "a lost-detection threshold shorter than the interval is refused"() {
        when:
        new BeatTiming(Duration.ofMinutes(5), Duration.ofMinutes(5).minusMillis(1))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('PT5M')
    }

    // The boundary: equal is the defaulting constructors' shape and is admitted.
    def "a lost-detection threshold equal to the interval is admitted"() {
        when:
        def timing = new BeatTiming(Duration.ofMinutes(5), Duration.ofMinutes(5))

        then:
        timing.interval() == Duration.ofMinutes(5)
        timing.lostDetection() == Duration.ofMinutes(5)
    }

    // FR13 of harden-task-branch-contract: the one production derivation carries the configured
    //     interval and the same lost-detection threshold LeaseThresholds states
    def "the lease thresholds build the timing from the tracker config"() {
        given:
        def config = new TrackerConfig('inmemory', 3, Duration.ofMinutes(5), 3, [:])

        when:
        def timing = LeaseThresholds.beatTiming(config)

        then:
        timing.interval() == Duration.ofMinutes(5)
        timing.lostDetection() == LeaseThresholds.lostDetection(config)
    }
}
