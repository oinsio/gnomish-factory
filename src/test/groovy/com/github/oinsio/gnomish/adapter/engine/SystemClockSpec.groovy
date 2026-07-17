package com.github.oinsio.gnomish.adapter.engine

import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * D10, M2 of add-manual-run: a plain direct spec for the production {@link Clock}
 * environment-port adapter. No abstract port-contract ceremony — {@code Clock} is a
 * one-method wrapper around the system clock and there is exactly one production
 * implementation to assert against.
 */
class SystemClockSpec extends Specification {

    def "now() returns an instant close to the system clock"() {
        given:
        def clock = new SystemClock()

        when:
        def before = Instant.now()
        def result = clock.now()
        def after = Instant.now()

        then: 'result falls within the [before, after] window, generously bounded'
        !result.isBefore(before.minus(Duration.ofSeconds(1)))
        !result.isAfter(after.plus(Duration.ofSeconds(1)))
    }

    def "successive calls are monotonically non-decreasing"() {
        given:
        def clock = new SystemClock()

        when:
        def first = clock.now()
        def second = clock.now()

        then:
        !second.isBefore(first)
    }
}
