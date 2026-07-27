package com.github.oinsio.gnomish.domain.pipeline

import spock.lang.Specification

/**
 * TrackerConfigRule: the pure tracker core-key check (design D6) over the
 * TrackerConfig the model carries from config.yaml. Contract: the pipeline-config
 * spec fixes abort-threshold as a positive integer (default 3). The mapper (task
 * 3.1) substitutes 3 only when the key is omitted, so a declared 0 or negative
 * flows through unchanged and must be flagged here — one located ConfigError
 * naming config.yaml and the tracker.abort-threshold field (NFR-O1, UX2) —
 * mirroring how StageSanityRule flags a non-positive attempt limit. An absent
 * tracker section (null) is valid: the section is optional.
 * Implements FR17 of add-tracker-port.
 */
class TrackerConfigRuleSpec extends Specification {

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
}
