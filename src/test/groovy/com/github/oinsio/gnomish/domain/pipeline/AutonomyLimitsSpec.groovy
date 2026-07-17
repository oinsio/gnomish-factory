package com.github.oinsio.gnomish.domain.pipeline

import spock.lang.Specification

/**
 * AutonomyLimits: the resolved autonomy limits of a stage — attempt limit only,
 * token budgets deferred (NG8). Resolution semantics (FR7): a per-stage
 * override wins over the config.yaml default; the default applies when the
 * stage declares none. The record carries the resolved value without range
 * enforcement — the "&ge; 1" rule is a located pure-validator concern (design
 * D6, task 4.4), never a constructor exception.
 * Implements FR7 of load-pipeline-config.
 */
class AutonomyLimitsSpec extends Specification {

    // FR7: override wins when present, default applies when there is none
    def "resolves #resolved from default #defaultLimit and override #override"() {
        expect: 'resolution picks the stage override when present, else the default'
        AutonomyLimits.resolve(defaultLimit, override) == new AutonomyLimits(resolved)

        where:
        defaultLimit | override | resolved
        3            | 5        | 5 // override wins over the default
        5            | 1        | 1 // override wins even when lower
        3            | null     | 3 // no override: the config.yaml default applies
        1            | null     | 1 // smallest valid default passes through unchanged
    }

    // FR7/D6: range checking belongs to the pure validators (task 4.4) as a
    // located ConfigError — the record must carry an out-of-range value so the
    // validator can still see and report it
    def "carries an out-of-range resolved value without throwing"() {
        when: 'resolution yields a limit below 1 (invalid per FR7)'
        def limits = AutonomyLimits.resolve(0, override as Integer)

        then: 'the record carries the value untouched for the validator to report'
        notThrown(Exception)
        limits.attemptLimit() == resolved

        where:
        override | resolved
        -2       | -2 // invalid override is preserved, not masked by the default
        null     | 0 //  invalid default is preserved as well
    }

    // FR7: resolved limits are values — StageDefinitions compare by content
    def "limits with the same attempt limit are equal values"() {
        expect: 'two independently resolved limits with equal values are equal'
        AutonomyLimits.resolve(3, null) == AutonomyLimits.resolve(7, 3)
    }
}
