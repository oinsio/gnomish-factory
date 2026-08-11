package com.github.oinsio.gnomish

import spock.lang.Specification

/**
 * ResourceLimits: immutable typed configuration record for the container
 * adapter's resource limits (design D2, FR10 of add-sandbox-core). Validation is
 * plain Java in the compact constructor, mirroring ServePropertiesSpec — no
 * Spring context needed here. Every knob defaults when unset (null for strings,
 * 0 for the primitive pids), and an explicitly blank/negative value is a
 * configuration mistake that is rejected with the property name.
 *
 * FR10: the limits are carried, typed, with documented defaults.
 */
class ResourceLimitsSpec extends Specification {

    // FR10: the all-defaults factory is the documented default set
    def "defaults() carries the documented default limits"() {
        when: 'the all-defaults limits are built'
        def limits = ResourceLimits.defaults()

        then: 'every knob is its documented default'
        limits.cpus() == '2'
        limits.memory() == '2g'
        limits.pids() == 512L
        limits.disk() == '10g'
    }

    // FR10: explicit values are exposed unchanged
    def "explicit limit values are exposed unchanged"() {
        when: 'limits are built with explicit values'
        def limits = new ResourceLimits('4', '8g', 1024L, '50g')

        then: 'the accessors return exactly the configured values'
        limits.cpus() == '4'
        limits.memory() == '8g'
        limits.pids() == 1024L
        limits.disk() == '50g'
    }

    // FR10: each string knob defaults when unset (null)
    def "#knob defaults to #expected when unset"() {
        expect: 'the unset string knob resolves to its documented default'
        accessor.call(new ResourceLimits(cpus, memory, 0L, disk)) == expected

        where:
        knob     | cpus | memory | disk | expected || accessor
        'cpus'   | null | 'x'    | 'x'  | '2'      || { it.cpus() }
        'memory' | 'x'  | null   | 'x'  | '2g'     || { it.memory() }
        'disk'   | 'x'  | 'x'    | null | '10g'    || { it.disk() }
    }

    // FR10: a blank string knob is a configuration mistake, rejected with its name
    def "blank #knob is rejected with the property name in the message"() {
        when: 'a limits record is built with a blank string knob'
        new ResourceLimits(cpus, memory, 0L, disk)

        then: 'construction fails and the message names the knob'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains("factory.sandbox.limits.$knob")

        where:
        knob     | cpus | memory | disk
        'cpus'   | ' '  | 'x'    | 'x'
        'memory' | 'x'  | ''     | 'x'
        'disk'   | 'x'  | 'x'    | '  '
    }

    // FR10: pids defaults to 512 when unset (primitive 0 sentinel)
    def "pids defaults to 512 when unset (0)"() {
        expect: 'the unset pids count resolves to the documented default'
        new ResourceLimits('x', 'x', 0L, 'x').pids() == 512L
    }

    // FR10: pins the pids boundary — 1 (smallest positive) kept, 0 defaults, negatives rejected
    def "pids of #value is #outcome"() {
        when: 'a limits record is built with the pids value'
        def limits = new ResourceLimits('x', 'x', value, 'x')

        then: 'the boundary behaves as documented'
        limits.pids() == expected

        where:
        value | outcome              || expected
        1L    | 'kept unchanged'     || 1L
        999L  | 'kept unchanged'     || 999L
    }

    // FR10: a negative pids count is rejected with the property name
    def "negative pids #value is rejected with the property name in the message"() {
        when: 'a limits record is built with a negative pids count'
        new ResourceLimits('x', 'x', value, 'x')

        then: 'construction fails and the message names factory.sandbox.limits.pids'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('factory.sandbox.limits.pids')

        where:
        value << [-1L, -512L]
    }

    // FR10: the properties type is an immutable record without setters
    def "the limits type is an immutable record without setter methods"() {
        expect: 'it is a Java record'
        ResourceLimits.isRecord()

        and: 'no public method follows the mutable setter convention'
        ResourceLimits.methods.every { !(it.name.startsWith('set') && it.parameterCount > 0) }
    }
}
