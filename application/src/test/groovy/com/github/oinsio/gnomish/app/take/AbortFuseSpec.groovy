package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.app.port.tracker.Tracker
import java.time.Clock
import spock.lang.Specification

/**
 * AbortFuse: the abort handler and its threshold K as one value — the only pairing of the two
 * that reaches the crash arm and the engine executions (FR2 of introduce-slot-wiring). A fuse that
 * could never trip, or trips before the first abort, is refused at construction.
 *
 * FR9, FR12 of add-tracker-port; FR2 of introduce-slot-wiring.
 */
class AbortFuseSpec extends Specification {

    private AbortHandler handler = new AbortHandler(Stub(Tracker), Clock.systemUTC())

    def "FR2: a non-positive threshold is refused at construction"() {
        when:
        new AbortFuse(handler, threshold)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "abort-fuse threshold must be positive, got ${threshold}"

        where:
        threshold << [0, -1]
    }

    def "FR2: a positive threshold is carried together with its handler"() {
        when:
        def fuse = new AbortFuse(handler, 1)

        then:
        fuse.handler().is(handler)
        fuse.threshold() == 1
    }
}
