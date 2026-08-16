package com.github.oinsio.gnomish.app.lease

import spock.lang.Specification

/**
 * FR2, D2 of add-claim-heartbeat: a plain direct spec for the production {@link
 * MonotonicTime} seam. {@code MonotonicTime} is a one-method wrapper over {@link
 * System#nanoTime()} with a single production implementation to assert against.
 */
class SystemMonotonicTimeSpec extends Specification {

    // FR2, D2: nanoTime() returns a reading of the JVM's monotonic counter, bounded by
    //     System.nanoTime() readings taken immediately around it.
    def "nanoTime() returns a reading close to System.nanoTime()"() {
        given:
        def time = new SystemMonotonicTime()

        when:
        def before = System.nanoTime()
        def reading = time.nanoTime()
        def after = System.nanoTime()

        then: 'the reading falls within the [before, after] window'
        reading - before >= 0
        after - reading >= 0
    }

    // FR2, D2: successive readings never go backwards - the counter is monotonic
    def "successive readings are non-decreasing"() {
        given:
        def time = new SystemMonotonicTime()

        when:
        def first = time.nanoTime()
        def second = time.nanoTime()

        then:
        second - first >= 0
    }
}
