package com.github.oinsio.gnomish.status

import java.time.Duration
import spock.lang.Specification

/**
 * {@link WallTime#since}: the one place the summary's {@code wall} is measured, for all
 * three assemblers of a summary (the serve slot, the manual-run dispatcher, the engine-event
 * accumulator). It is a shared method rather than three subtractions because the rule they must
 * agree on is which clock, not which arithmetic.
 *
 * <p>Implements FR3 of harden-logging-observability.
 */
class WallTimeSpec extends Specification {

    // The wall time is the interval between two readings of a monotonic source, so a clock stepped
    // by NTP mid-run cannot make a task report a negative — or an absurd — duration. Bounded on
    // both sides: a lower bound alone would also hold for the sum of the readings, not the gap.
    def "since measures the interval between two monotonic readings, not their sum"() {
        given: 'a start reading taken one second in the past'
        long oneSecondAgo = System.nanoTime() - Duration.ofSeconds(1).toNanos()

        when:
        def elapsed = WallTime.since(oneSecondAgo)

        then:
        elapsed >= Duration.ofSeconds(1)
        elapsed <Duration.ofSeconds(2)
    }

    // The record's own constructor rejects a negative wall, so the measurement feeding it must be
    // one a monotonic source cannot make negative — a reading taken now is a zero-or-more duration.
    def "since, for a reading taken now is never negative"() {
        expect:
        !WallTime.since(System.nanoTime()).isNegative()
    }
}
