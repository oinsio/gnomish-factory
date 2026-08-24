package com.github.oinsio.gnomish.dashboard

import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.emptyHistory
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.neverFetchedBoard
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.noSweepData

import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Verifies the page's skeleton and its priority ordering (tasks 3.1, 4.1,
 * 4.3 of redesign-dashboard): the fixed block order, the mode / generated-at
 * / stale-after data attributes the static script reads, the watch-only
 * meta-refresh, and the freshness strip.
 *
 * <p>Each block has its own spec ({@code DashboardStatusCardSpec},
 * {@code DashboardAttentionBlockSpec}, {@code DashboardInProgressBlockSpec},
 * {@code DashboardBoardBlockDegradationSpec}, {@code DashboardOutcomesBlockSpec},
 * {@code DashboardTokensBlockSpec}, {@code DashboardHygieneBlockSpec}).
 *
 * FR1, FR2, FR3, FR10 of redesign-dashboard.
 */
class DashboardHtmlRendererSpec extends Specification {

    def renderer = new DashboardHtmlRenderer()

    private static final Instant GENERATED_AT = Instant.parse('2026-08-06T09:00:00Z')

    // FR1: the order IS the argument — "is anything waiting for me?" is answered by the top layers.
    def "blocks render in the fixed priority order, quieter reference blocks last"() {
        when:
        def html = renderer.render(
                new DaemonSnapshotView.Absent(), emptyHistory(), neverFetchedBoard(), noSweepData(),
                GENERATED_AT, null)

        then:
        def order = [
            'id="freshness"',
            'id="status"',
            'id="attention"',
            'id="in-progress"',
            'id="outcomes"',
            'id="tokens"',
            'id="hygiene"'
        ].collect {
            html.indexOf(it)
        }
        order.every { it >= 0 }
        order == order.toSorted()
    }

    // FR2: every block occupies its position regardless of data — nothing appears or disappears.
    def "an entirely empty page still renders every block, each with its own empty-state sentence"() {
        when:
        def html = renderer.render(
                new DaemonSnapshotView.Absent(), emptyHistory(), neverFetchedBoard(), noSweepData(),
                GENERATED_AT, null)

        then:
        html.contains('Board unavailable')
        html.contains('No finished tasks yet')
        html.contains('No token usage recorded yet')
        html.contains('Sandbox sweep has not run yet')

        and: 'no bare empty list is left behind where a block had nothing to show'
        !html.contains('<div class="row"></div>')
    }

    @Unroll
    def "watch mode bakes #expectedMode into the body and #refreshDescription"() {
        when:
        def html = renderer.render(
                new DaemonSnapshotView.Absent(), emptyHistory(), neverFetchedBoard(), noSweepData(),
                GENERATED_AT, cadence)

        then: 'the static script reads its mode and the page age from the body, never from a templated literal'
        html.contains('data-mode="' + expectedMode + '"')
        html.contains('data-generated-at="' + GENERATED_AT.toEpochMilli() + '"')

        and: 'a watch page also bakes its stale threshold — three times its own cadence (FR3)'
        html.contains('data-stale-after="30000"') == metaRefresh
        html.contains('data-stale-after') == metaRefresh

        and:
        html.contains('<meta http-equiv="refresh"') == metaRefresh

        where:
        cadence | expectedMode | metaRefresh | refreshDescription
        Duration.ofSeconds(10) | 'watch' | true | 'carries a meta-refresh'
        null | 'oneshot' | false | 'carries none'
    }

    def "the meta-refresh interval and the stale threshold are both the render cadence's own"() {
        when:
        def html = renderer.render(
                new DaemonSnapshotView.Absent(), emptyHistory(), neverFetchedBoard(), noSweepData(),
                GENERATED_AT, Duration.ofSeconds(30))

        then:
        html.contains('<meta http-equiv="refresh" content="30">')
        html.contains('data-stale-after="90000"')
    }

    // A cadence under a second truncates to content="0" — a reload storm, not a refresh.
    @Unroll
    def "a render cadence of #cadence is rejected rather than baked into the meta-refresh"() {
        when:
        renderer.render(
                new DaemonSnapshotView.Absent(), emptyHistory(), neverFetchedBoard(), noSweepData(),
                GENERATED_AT, cadence)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('renderCadence')

        where:
        cadence << [
            Duration.ofMillis(999),
            Duration.ZERO,
            Duration.ofSeconds(-1)
        ]
    }

    // The boundary the guard must let through: one second is the shortest usable cadence.
    def "a one-second render cadence is accepted and baked verbatim"() {
        when:
        def html = renderer.render(
                new DaemonSnapshotView.Absent(), emptyHistory(), neverFetchedBoard(), noSweepData(),
                GENERATED_AT, Duration.ofSeconds(1))

        then:
        html.contains('<meta http-equiv="refresh" content="1">')
    }

    // FR3: the strip is server-rendered fresh with the absolute instant, so a no-JS reader sees it.
    def "the freshness strip carries both icons and the page's absolute generated instant"() {
        when:
        def html = renderer.render(
                new DaemonSnapshotView.Absent(), emptyHistory(), neverFetchedBoard(), noSweepData(),
                GENERATED_AT, Duration.ofSeconds(10))

        then:
        html.contains('<div class="freshness" id="freshness" data-state="fresh" role="status" aria-live="polite">')
        html.contains('freshness__icon--fresh')
        html.contains('freshness__icon--stale')
        html.contains('<span id="freshness-text">updated <time datetime="' + GENERATED_AT + '" ' +
                'data-epoch="' + GENERATED_AT.toEpochMilli() + '">' + GENERATED_AT + '</time></span>')
    }

    @Unroll
    def "a null #param is refused rather than rendered as a broken page"() {
        when:
        renderer.render(daemon, history, board, hygiene, generated, null)

        then:
        def error = thrown(NullPointerException)
        error.message == param

        where:
        param | daemon | history | board | hygiene | generated
        'daemonView' | null | emptyHistory() | neverFetchedBoard() | noSweepData() | GENERATED_AT
        'historyView' | new DaemonSnapshotView.Absent() | null | neverFetchedBoard() | noSweepData() | GENERATED_AT
        'boardView' | new DaemonSnapshotView.Absent() | emptyHistory() | null | noSweepData() | GENERATED_AT
        'hygieneView' | new DaemonSnapshotView.Absent() | emptyHistory() | neverFetchedBoard() | null | GENERATED_AT
        'generatedAt' | new DaemonSnapshotView.Absent() | emptyHistory() | neverFetchedBoard() | noSweepData() | null
    }
}
