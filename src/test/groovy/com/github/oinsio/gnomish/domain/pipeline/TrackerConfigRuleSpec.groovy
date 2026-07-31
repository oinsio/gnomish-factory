package com.github.oinsio.gnomish.domain.pipeline

import java.time.Duration
import spock.lang.Specification

/**
 * TrackerConfigRule: the pure tracker core-key check (design D6) over the
 * TrackerConfig the model carries from config.yaml. Contract: the pipeline-config
 * spec fixes abort-threshold as a positive integer (default 3), heartbeat-interval
 * as a positive duration (default 5 min), and heartbeat-ttl-multiplier as an
 * integer ≥ 3 (default 3). The mapper substitutes the defaults only when a key is
 * omitted, so a declared out-of-range value flows through unchanged and must be
 * flagged here — one located ConfigError per fault naming config.yaml and the
 * field (NFR-O1, UX2) — mirroring how StageSanityRule flags a non-positive value.
 * Faults aggregate in abort/multiplier/interval order (FR8). An absent tracker
 * section (null) is valid: the section is optional.
 * Implements FR17 of add-tracker-port, FR3 of add-claim-heartbeat.
 */
class TrackerConfigRuleSpec extends Specification {

    private static TrackerConfig tracker(int threshold, Duration interval, int multiplier) {
        new TrackerConfig('github', threshold, interval, multiplier, [:])
    }

    // FR17: an absent tracker section is valid — no errors
    def "an absent tracker section produces no errors"() {
        expect: 'validating a null tracker yields an empty error list'
        TrackerConfigRule.validate(null) == []
    }

    // FR17 delta-spec "positive integer": a positive abort-threshold is accepted
    def "a positive abort-threshold #threshold produces no errors"() {
        expect: 'validating a positive threshold yields an empty error list'
        TrackerConfigRule.validate(new TrackerConfig('github', threshold)) == []

        where:
        threshold << [1, 3, 5, 100]
    }

    // FR17 delta-spec "positive integer": a declared 0 or negative abort-threshold
    // is a located error naming config.yaml and the tracker.abort-threshold field
    def "a non-positive abort-threshold #threshold is a located error naming config.yaml"() {
        expect: 'exactly one error locating config.yaml: tracker.abort-threshold'
        TrackerConfigRule.validate(new TrackerConfig('github', threshold)) == [
            new ConfigError('config.yaml', 'tracker.abort-threshold',
            "non-positive abort-threshold $threshold; the threshold must be a positive integer" as String)
        ]

        where:
        threshold << [0, -1, -3, Integer.MIN_VALUE]
    }

    // FR3 delta-spec: a multiplier at or above the floor of 3 is accepted
    def "a heartbeat-ttl-multiplier #multiplier at or above the floor produces no errors"() {
        expect: 'validating a multiplier >= 3 with valid interval yields no errors'
        TrackerConfigRule.validate(tracker(3, Duration.ofMinutes(5), multiplier)) == []

        where:
        multiplier << [3, 4, 5, 100]
    }

    // FR3 delta-spec scenario "Multiplier below the floor is a load error": a
    // declared multiplier < 3 is a located error naming config.yaml and the
    // minimum of 3
    def "a heartbeat-ttl-multiplier #multiplier below the floor is a located error naming the minimum of 3"() {
        expect: 'exactly one error locating config.yaml: tracker.heartbeat-ttl-multiplier'
        TrackerConfigRule.validate(tracker(3, Duration.ofMinutes(5), multiplier)) == [
            new ConfigError('config.yaml', 'tracker.heartbeat-ttl-multiplier',
            "heartbeat-ttl-multiplier $multiplier below the minimum of 3; the multiplier must be at least 3 so TTL = multiplier × interval stays consistent" as String)
        ]

        where:
        multiplier << [2, 1, 0, -1]
    }

    // FR3: a positive heartbeat-interval is accepted
    def "a positive heartbeat-interval #interval produces no errors"() {
        expect: 'validating a positive interval with valid multiplier yields no errors'
        TrackerConfigRule.validate(tracker(3, interval, 3)) == []

        where:
        interval << [
            Duration.ofSeconds(1),
            Duration.ofMinutes(5),
            Duration.ofHours(2)
        ]
    }

    // FR3: a non-positive heartbeat-interval is a located error naming config.yaml
    def "a non-positive heartbeat-interval #interval is a located error naming config.yaml"() {
        expect: 'exactly one error locating config.yaml: tracker.heartbeat-interval'
        TrackerConfigRule.validate(tracker(3, interval, 3)) == [
            new ConfigError('config.yaml', 'tracker.heartbeat-interval',
            "non-positive heartbeat-interval $interval; the interval must be positive" as String)
        ]

        where:
        interval << [
            Duration.ZERO,
            Duration.ofSeconds(-1),
            Duration.ofMinutes(-5)
        ]
    }

    // FR3 delta-spec scenario "Heartbeat errors aggregate": every applicable
    // core-key fault surfaces together in abort/multiplier/interval order (FR8)
    def "abort, multiplier and interval faults aggregate in a fixed order"() {
        expect: 'all three located errors, abort-threshold then multiplier then interval'
        TrackerConfigRule.validate(new TrackerConfig('github', 0, Duration.ZERO, 1, [:])) == [
            new ConfigError('config.yaml', 'tracker.abort-threshold',
            'non-positive abort-threshold 0; the threshold must be a positive integer'),
            new ConfigError('config.yaml', 'tracker.heartbeat-ttl-multiplier',
            'heartbeat-ttl-multiplier 1 below the minimum of 3; the multiplier must be at least 3 so TTL = multiplier × interval stays consistent'),
            new ConfigError('config.yaml', 'tracker.heartbeat-interval',
            'non-positive heartbeat-interval PT0S; the interval must be positive')
        ]
    }
}
