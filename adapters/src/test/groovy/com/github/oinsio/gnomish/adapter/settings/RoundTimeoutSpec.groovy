package com.github.oinsio.gnomish.adapter.settings

import java.time.Duration
import spock.lang.Specification

/**
 * FR11, FR13, D7: {@link RoundTimeout} reads the {@code roundTimeout} a stage executor's or judge
 * check's opaque settings map declares — a numeric seconds value, an ISO-8601 duration string (with or
 * without the {@code PT} prefix, case-insensitively).
 *
 * <p>FR7, D6 of extract-agent-round-runner (as amended 2026-11-21): the load-time judge of the same
 * shapes lives here too, behind the same parse, so what the judge accepts is exactly what the reader
 * reads; and the reader answers "declared or not" with no default of its own — the factory's overall
 * bound, written once in {@code ProgramBounds.DEFAULTS}, is {@code RoundBounds}' to apply.
 */
class RoundTimeoutSpec extends Specification {

    def "a numeric roundTimeout is declared as that many seconds"() {
        expect:
        RoundTimeout.declared([roundTimeout: 45]) == Optional.of(Duration.ofSeconds(45))
    }

    def "an ISO-8601 duration string with the PT prefix already present is read as-is"() {
        expect:
        RoundTimeout.declared([roundTimeout: 'PT30S']) == Optional.of(Duration.ofSeconds(30))
    }

    def "a lowercase pt prefix is accepted unchanged"() {
        expect:
        RoundTimeout.declared([roundTimeout: 'pt30s']) == Optional.of(Duration.ofSeconds(30))
    }

    def "a bare unit string without a PT prefix is uppercased and prefixed"() {
        expect:
        RoundTimeout.declared([roundTimeout: '45s']) == Optional.of(Duration.ofSeconds(45))
    }

    // FR7, D6 of extract-agent-round-runner: no fallback here — absent is "not declared"
    def "an absent roundTimeout key is not declared"() {
        expect:
        RoundTimeout.declared([:]) == Optional.empty()
    }

    // D6: a value the load-time judge refuses declares nothing a round could be bound by; a loaded
    //     law never carries one
    def "a value with no accepted shape (#value) declares nothing"() {
        expect:
        RoundTimeout.declared([roundTimeout: value]) == Optional.empty()

        where:
        value << [
            'not-a-duration',
            [1, 2, 3],
            '   ',
            true
        ]
    }

    // FR3 of add-command-executor, D6 of extract-agent-round-runner: the accepted shapes are the ones
    //     the load-time judge accepted before the pair dissolved
    def "D6: the judge accepts #value"() {
        expect:
        RoundTimeout.malformation(value) == null

        where:
        value << [
            30,
            2.5,
            'PT30S',
            'pt1m',
            '45s'
        ]
    }

    def "D6: the judge refuses #value with the one malformed-value message"() {
        expect:
        RoundTimeout.malformation(value) ==
                "malformed 'roundTimeout': expected a number of seconds or an ISO-8601 duration string"

        where:
        value << [
            true,
            'not-a-duration',
            '',
            '   ',
            null,
            [1, 2]
        ]
    }

    // D6: one parse behind both — a value the judge accepts is declared as what it names
    def "D6: every accepted shape is declared as the duration it names"() {
        expect:
        RoundTimeout.declared([roundTimeout: value]) == Optional.of(expected)

        where:
        value || expected
        90 || Duration.ofSeconds(90)
        'PT2M' || Duration.ofMinutes(2)
        '10m' || Duration.ofMinutes(10)
    }
}
