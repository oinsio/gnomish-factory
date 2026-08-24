package com.github.oinsio.gnomish.dashboard

import static com.github.oinsio.gnomish.testsupport.DashboardPageMarkup.markup
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.emptyHistory
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.neverFetchedBoard
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.noSweepData

import com.github.oinsio.gnomish.serveobservability.SweepCounts
import com.github.oinsio.gnomish.serveobservability.SweepVital
import java.time.Instant
import spock.lang.Specification

/**
 * Verifies the sandbox-hygiene block, the page's quietest reference block
 * (task 3.7 of redesign-dashboard): a normal card with the last tick's
 * four-group breakdown when sweep data exists, and a dashed-border footnote —
 * not an empty card — when it does not.
 *
 * FR1, FR2, FR8 of redesign-dashboard.
 */
class DashboardHygieneBlockSpec extends Specification {

    def renderer = new DashboardHtmlRenderer()

    private static final Instant GENERATED_AT = Instant.parse('2026-08-06T09:00:00Z')

    def "a completed tick renders the four groups as the quietest block"() {
        given:
        def sweep = new SweepVital(GENERATED_AT, 300L, new SweepCounts(1, 0, 0, 2, 0, 0), [], 0, 0)

        when:
        def html = renderer.render(
                new DaemonSnapshotView.Absent(), emptyHistory(), neverFetchedBoard(),
                new SandboxHygieneView(sweep, []), GENERATED_AT, null)

        then: 'checkedAlive(1) + keptUnderThreshold(0) = 1 checked, disposedAged(2) = 2 cleaned'
        html.contains('<span class="row__label">cleaned</span><span class="row__count num" title="2">2</span>')
        html.contains(
                '<span class="row__label">checked and untouched</span><span class="row__count num" title="1">1</span>')

        and: 'the block carries its own heading and the tick it is reporting on'
        html.contains('<h2 class="card__title">Sandbox hygiene</h2>')
        html.contains('last tick: <time datetime="' + GENERATED_AT + '" data-epoch="'
                + GENERATED_AT.toEpochMilli() + '">' + GENERATED_AT + '</time>')

        and: 'the footnote treatment is gone once there is data to show'
        html.contains('<section class="card" id="hygiene">')
        !markup(html).contains('class="footnote"')
        !html.contains('Sandbox sweep has not run yet')
    }

    // FR2: no data renders as the dashed footnote element, never as an empty card
    //      that would read as a sweep reporting nothing.
    def "no sweep data renders the dashed-border footnote, not an empty card"() {
        when:
        def html = renderer.render(
                new DaemonSnapshotView.Absent(), emptyHistory(), neverFetchedBoard(), noSweepData(),
                GENERATED_AT, null)

        then:
        html.contains('<div class="footnote" id="hygiene">')
        html.contains('Sandbox sweep has not run yet')

        and: 'the footnote replaces the card element entirely'
        !markup(html).contains('<section class="card" id="hygiene">')
    }
}
