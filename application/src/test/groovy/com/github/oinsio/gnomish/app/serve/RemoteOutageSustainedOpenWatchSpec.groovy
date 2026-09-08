package com.github.oinsio.gnomish.app.serve

import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR14, NFR-O1 of add-base-ref-resolution: {@link RemoteOutageSustainedOpenWatch} in isolation —
 * the one-shot latch fires exactly at its threshold (never a tick early), never twice for the same
 * outage, and {@link RemoteOutageSustainedOpenWatch#reset} re-arms it for the next one.
 */
class RemoteOutageSustainedOpenWatchSpec extends Specification {

    private static final Duration THRESHOLD = Duration.ofHours(1)
    private static final Instant OPENED_AT = Instant.parse('2026-01-01T00:00:00Z')

    def "threshold reports the exact configured duration"() {
        expect:
        new RemoteOutageSustainedOpenWatch(THRESHOLD).threshold() == THRESHOLD
    }

    def "does not fire a moment before the threshold"() {
        given:
        def watch = new RemoteOutageSustainedOpenWatch(THRESHOLD)

        expect:
        !watch.shouldFire(OPENED_AT, OPENED_AT + THRESHOLD.minusSeconds(1))
    }

    def "fires exactly at the threshold"() {
        given:
        def watch = new RemoteOutageSustainedOpenWatch(THRESHOLD)

        expect:
        watch.shouldFire(OPENED_AT, OPENED_AT + THRESHOLD)
    }

    def "never fires twice for the same outage"() {
        given:
        def watch = new RemoteOutageSustainedOpenWatch(THRESHOLD)
        watch.shouldFire(OPENED_AT, OPENED_AT + THRESHOLD)

        expect:
        !watch.shouldFire(OPENED_AT, OPENED_AT + THRESHOLD.multipliedBy(3))
    }

    def "reset re-arms the watch for a freshly opened outage"() {
        given:
        def watch = new RemoteOutageSustainedOpenWatch(THRESHOLD)
        watch.shouldFire(OPENED_AT, OPENED_AT + THRESHOLD)

        when:
        watch.reset()

        then:
        watch.shouldFire(OPENED_AT, OPENED_AT + THRESHOLD)
    }
}
