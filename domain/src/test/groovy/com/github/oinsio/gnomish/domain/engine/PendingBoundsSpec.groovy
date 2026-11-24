package com.github.oinsio.gnomish.domain.engine

import java.time.Duration
import spock.lang.Specification

/**
 * The factory's bounds on a pending {@code transform} round's wait (FR10, FR11 of
 * extract-agent-round-runner; design D11): the defaults the factory runs with, and the refusal of a
 * missing or non-positive bound.
 */
class PendingBoundsSpec extends Specification {

    // FR10 (D11): the factory polls a pending round every 30 seconds at most and gives up after 24 hours
    def "the factory's pending bounds are a 30-second interval and a 24-hour ceiling"() {
        expect:
        PendingBounds.DEFAULTS == new PendingBounds(Duration.ofSeconds(30), Duration.ofHours(24))
    }

    // FR10 (D11): an engine assembled without naming its bounds runs on the factory's defaults
    def "ports built without bounds carry the factory's defaults"() {
        expect:
        new EnginePorts(null, null, null, null, null, null, null).pendingBounds() == PendingBounds.DEFAULTS
    }

    // FR10: a bound that is missing, zero or negative is refused, naming the bound
    def "a #name of #value is refused"() {
        when:
        name == 'interval' ? new PendingBounds(value, Duration.ofHours(1)) : new PendingBounds(Duration.ofSeconds(1), value)

        then:
        def e = thrown(RuntimeException)
        e.message.contains(name)

        where:
        name | value
        'interval' | null
        'interval' | Duration.ZERO
        'interval' | Duration.ofSeconds(-1)
        'ceiling' | null
        'ceiling' | Duration.ZERO
        'ceiling' | Duration.ofSeconds(-1)
    }
}
