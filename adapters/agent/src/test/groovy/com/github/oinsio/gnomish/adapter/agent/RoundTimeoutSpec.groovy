package com.github.oinsio.gnomish.adapter.agent

import java.time.Duration
import spock.lang.Specification

/**
 * FR11, FR13, D7: {@link RoundTimeout} resolves the process-kill budget
 * tolerantly from a stage executor's or judge check's opaque settings map —
 * a numeric seconds value, an ISO-8601 duration string (with or without the
 * {@code PT} prefix, case-insensitively), or a fallback to {@link
 * RoundTimeout#DEFAULT} when the key is absent or unparseable.
 */
class RoundTimeoutSpec extends Specification {

    def "a numeric roundTimeout resolves to that many seconds"() {
        when:
        def resolved = RoundTimeout.resolve([roundTimeout: 45])

        then:
        resolved == Duration.ofSeconds(45)
    }

    def "an ISO-8601 duration string with the PT prefix already present resolves as-is"() {
        when:
        def resolved = RoundTimeout.resolve([roundTimeout: 'PT30S'])

        then:
        resolved == Duration.ofSeconds(30)
    }

    def "a lowercase pt prefix is accepted unchanged"() {
        when:
        def resolved = RoundTimeout.resolve([roundTimeout: 'pt30s'])

        then:
        resolved == Duration.ofSeconds(30)
    }

    def "a bare unit string without a PT prefix is uppercased and prefixed"() {
        when:
        def resolved = RoundTimeout.resolve([roundTimeout: '45s'])

        then:
        resolved == Duration.ofSeconds(45)
    }

    def "an absent roundTimeout key falls back to DEFAULT"() {
        when:
        def resolved = RoundTimeout.resolve([:])

        then:
        resolved == RoundTimeout.DEFAULT
    }

    def "an unparseable ISO duration string falls back to DEFAULT"() {
        when:
        def resolved = RoundTimeout.resolve([roundTimeout: 'not-a-duration'])

        then:
        resolved == RoundTimeout.DEFAULT
    }

    def "a value of an unsupported type falls back to DEFAULT"() {
        when:
        def resolved = RoundTimeout.resolve([roundTimeout: [1, 2, 3]])

        then:
        resolved == RoundTimeout.DEFAULT
    }

    def "a blank string value falls back to a zero-second duration via the PT0S default text"() {
        when:
        def resolved = RoundTimeout.resolve([roundTimeout: '   '])

        then:
        resolved == Duration.ZERO
    }
}
