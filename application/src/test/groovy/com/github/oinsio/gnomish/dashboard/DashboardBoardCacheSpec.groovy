package com.github.oinsio.gnomish.dashboard

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.board.BoardModel
import com.github.oinsio.gnomish.board.ReadySummary
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR3, FR9, NFR-P1 of add-dashboard-page (task 4.4, design D9): {@link DashboardBoardCache} keeps
 * the last successfully fetched {@link BoardModel} across cycles, gates refresh on a caller-given
 * cadence, and degrades to the last cached model (or {@code null} if none exists yet) plus a
 * failure notice when a fetch throws.
 */
class DashboardBoardCacheSpec extends Specification {

    private static final Instant T0 = Instant.parse('2026-08-06T00:00:00Z')
    private static final Duration CADENCE = Duration.ofSeconds(60)

    def model = new BoardModel([], [], [], new ReadySummary(0, 0, 0, 0, 0), false, T0)

    def "is due before any fetch has ever been attempted"() {
        expect:
        new DashboardBoardCache().dueFor(T0, CADENCE)
    }

    def "a successful refresh returns the fresh model with the fetch time and no failure"() {
        given:
        def cache = new DashboardBoardCache()

        when:
        def view = cache.refresh({ -> model }, T0)

        then:
        view.model() == model
        view.fetchedAt() == T0
        view.failureMessage() == null
    }

    def "is not due again until the cadence elapses since the last attempt"() {
        given:
        def cache = new DashboardBoardCache()
        cache.refresh({ -> model }, T0)

        expect:
        !cache.dueFor(T0.plus(Duration.ofSeconds(59)), CADENCE)
        cache.dueFor(T0.plus(Duration.ofSeconds(60)), CADENCE)
    }

    def "cached() reuses the last model and fetch time with no failure notice between refreshes"() {
        given:
        def cache = new DashboardBoardCache()
        cache.refresh({ -> model }, T0)

        when:
        def view = cache.cached()

        then:
        view.model() == model
        view.fetchedAt() == T0
        view.failureMessage() == null
    }

    def "a first-ever fetch failure degrades to unavailable: no model, no fetch time, the failure message"() {
        given:
        def cache = new DashboardBoardCache()
        def logs = LogCaptureSupport.attach(DashboardBoardCache, Level.DEBUG)

        when:
        def view = cache.refresh({
            -> throw new RuntimeException('tracker unreachable')
        }, T0)
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        view.model() == null
        view.fetchedAt() == null
        view.failureMessage() == 'tracker unreachable'

        and: 'FR5, FR7 of harden-logging-observability: the view carries the message, the log the stack'
        events.size() == 1
        events[0].level == Level.DEBUG
        events[0].formattedMessage.contains('serving the last good model')
        events[0].throwableProxy.message == 'tracker unreachable'
    }

    def "a refresh failure after a prior success keeps the cached model, fetch time, and the new failure"() {
        given:
        def cache = new DashboardBoardCache()
        cache.refresh({ -> model }, T0)
        def failAt = T0.plus(CADENCE)

        when:
        def view = cache.refresh({
            -> throw new RuntimeException('outage')
        }, failAt)

        then:
        view.model() == model
        view.fetchedAt() == T0
        view.failureMessage() == 'outage'

        and: 'the attempt itself still counts, so dueFor gates on it, not only on successes'
        !cache.dueFor(failAt, CADENCE)
    }

    def "a failure with no message falls back to the exception's simple class name"() {
        given:
        def cache = new DashboardBoardCache()

        when:
        def view = cache.refresh({ -> throw new IllegalStateException() }, T0)

        then:
        view.failureMessage() == 'IllegalStateException'
    }
}
