package com.github.oinsio.gnomish.domain.engine.time

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR22 of supervise-daemon-loops-and-embed-dashboard: the time equipment's two behaviors — the
 * deadline test every bounded loop asks ({@code remaining}) and the wait toward a deadline only
 * the pair can do ({@code sleepUntil}) — on virtual time.
 */
class TimeEquipmentSpec extends Specification {

    def clock = new VirtualClock(Instant.parse('2026-10-09T12:00:00Z'))
    def sleeper = new VirtualSleeper(clock)
    def time = new TimeEquipment(clock, sleeper)

    def "FR22: remaining is the time left until the deadline, clamped at zero once it is reached or passed"() {
        expect:
        time.remaining(clock.instant().plus(offset)) == expected

        where:
        offset || expected
        Duration.ofSeconds(90) || Duration.ofSeconds(90)
        Duration.ZERO || Duration.ZERO
        Duration.ofSeconds(-5) || Duration.ZERO
    }

    def "FR22: sleepUntil waits on the sleeper exactly the time the clock says is left"() {
        given:
        def deadline = clock.instant().plus(Duration.ofMinutes(3))

        when:
        time.sleepUntil(deadline)

        then:
        sleeper.slept == [Duration.ofMinutes(3)]
        clock.instant() == deadline
    }

    def "FR22: sleepUntil returns without sleeping when the deadline is already reached"() {
        when:
        time.sleepUntil(clock.instant().minus(offset))

        then:
        sleeper.slept.isEmpty()

        where:
        offset << [
            Duration.ZERO,
            Duration.ofSeconds(1)
        ]
    }
}
