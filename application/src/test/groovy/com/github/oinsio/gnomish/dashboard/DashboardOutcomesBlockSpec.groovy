package com.github.oinsio.gnomish.dashboard

import static com.github.oinsio.gnomish.testsupport.DashboardHistoryFixtures.render
import static com.github.oinsio.gnomish.testsupport.DashboardHistoryFixtures.segmentWidths
import static com.github.oinsio.gnomish.testsupport.DashboardPageMarkup.markup

import com.github.oinsio.gnomish.serveobservability.OutcomeCounts
import java.time.LocalDate
import spock.lang.Specification

/**
 * Verifies the outcome-mix block (tasks 3.5, 3.8 of redesign-dashboard):
 * bars that compare mixes rather than volumes, with compact counts carrying
 * their exact values.
 *
 * FR6, FR9 of redesign-dashboard.
 */
class DashboardOutcomesBlockSpec extends Specification {

    // FR6: full-width mix bars, never scaled by volume — a 2-outcome day and a 20-outcome day
    //      span the same width so their mixes compare directly.
    def "unequal days span the same width, differing only in their numbers"() {
        given:
        def history = new LedgerHistoryView(
                [
                    new DayOutcomeCounts(LocalDate.parse('2026-08-04'), new OutcomeCounts(1, 1, 0, 0)),
                    new DayOutcomeCounts(LocalDate.parse('2026-08-05'), new OutcomeCounts(10, 10, 0, 0))
                ],
                [:])

        when:
        def html = render(history)

        then: 'both days render the same two 50% segments'
        html.count('style="width:50%;background:var(--seg-delivered)"') == 2
        html.count('style="width:50%;background:var(--seg-waiting)"') == 2

        and: 'the totals carry the volume difference'
        html.contains('title="2">2</span>')
        html.contains('title="20">20</span>')

        and: 'no pixel width survives anywhere — the old magnitude bar is gone'
        !html.contains('px"')
    }

    def "a single-outcome day fills its bar, and zero categories emit no segment"() {
        given:
        def history = new LedgerHistoryView(
                [
                    new DayOutcomeCounts(LocalDate.parse('2026-08-05'), new OutcomeCounts(3, 0, 0, 0))
                ], [:])

        when:
        def html = render(history)

        then:
        html.contains('style="width:100%;background:var(--seg-delivered)"')

        and: 'the three empty categories emit no segment at all, not a zero-width one'
        markup(html).count('class="bar__seg"') == 1

        and: 'the mix is still legible without colour, through the bar role and label'
        html.contains('role="img" aria-label="3 delivered, 0 awaiting a human, 0 aborted, 0 revoked"')

        and: 'one shared legend covers every day'
        html.count('class="legend"') == 1
    }

    // FR6: the day total is the sum of all four categories, and the bar's aria-label names each.
    def "a four-category day totals every category and labels the bar with all four"() {
        given:
        def history = new LedgerHistoryView(
                [
                    new DayOutcomeCounts(LocalDate.parse('2026-08-05'), new OutcomeCounts(5, 3, 2, 10))
                ], [:])

        when:
        def html = render(history)

        then: '5 + 3 + 2 + 10 = 20'
        html.contains('<span class="num bar-head__total" title="20">20</span>')

        and:
        html.contains('role="img" aria-label="5 delivered, 3 awaiting a human, 2 aborted, 10 revoked"')

        and: 'each category takes its own share of the width'
        html.contains('style="width:25%;background:var(--seg-delivered)"')
        html.contains('style="width:15%;background:var(--seg-waiting)"')
        html.contains('style="width:10%;background:var(--seg-aborted)"')
        html.contains('style="width:50%;background:var(--seg-revoked)"')
    }

    // FR6: the bar is a mix — its segments must fill it exactly, and a rare
    //      outcome must stay on screen rather than round away to nothing.
    def "a rare outcome keeps a visible sliver and the segments still fill the bar"() {
        given: 'one aborted task among 300'
        def history = new LedgerHistoryView(
                [
                    new DayOutcomeCounts(LocalDate.parse('2026-08-05'), new OutcomeCounts(299, 0, 1, 0))
                ], [:])

        when:
        def html = render(history)

        then: 'the single aborted task is still drawn, at the minimum width'
        html.contains('style="width:1%;background:var(--seg-aborted)"')

        and: 'and the bar is exactly full — no unaccounted gap at its end'
        segmentWidths(html).sum() == 100
    }

    def "shares that do not divide evenly still fill the bar exactly"() {
        given: 'three equal thirds, which no independent rounding can add back up to 100'
        def history = new LedgerHistoryView(
                [
                    new DayOutcomeCounts(LocalDate.parse('2026-08-05'), new OutcomeCounts(1, 1, 1, 0))
                ], [:])

        when:
        def html = render(history)

        then:
        segmentWidths(html) == [34, 33, 33]
    }

    def "the one shared legend names all four categories with their own swatches"() {
        given:
        def history = new LedgerHistoryView(
                [
                    new DayOutcomeCounts(LocalDate.parse('2026-08-05'), new OutcomeCounts(1, 0, 0, 0))
                ], [:])

        when:
        def html = render(history)

        then:
        html.contains('<div class="legend">'
                + '<span><span class="legend__swatch" style="background:var(--seg-delivered)"></span>delivered</span>'
                + '<span><span class="legend__swatch" style="background:var(--seg-waiting)"></span>'
                + 'awaiting a human</span>'
                + '<span><span class="legend__swatch" style="background:var(--seg-aborted)"></span>aborted</span>'
                + '<span><span class="legend__swatch" style="background:var(--seg-revoked)"></span>revoked</span>'
                + '</div>')
    }

    def "the block states the window it covers and its empty state when there is none"() {
        given:
        def history = new LedgerHistoryView(
                [
                    new DayOutcomeCounts(LocalDate.parse('2026-08-04'), new OutcomeCounts(1, 0, 0, 0)),
                    new DayOutcomeCounts(LocalDate.parse('2026-08-05'), new OutcomeCounts(1, 0, 0, 0))
                ], [:])

        expect:
        render(history).contains('<span class="card__meta">2026-08-04 — 2026-08-05</span>')

        and: 'with no days there is no window to state, so the heading carries no meta slot at all'
        def empty = render(new LedgerHistoryView([], [:]))
        empty.contains('No finished tasks yet')
        empty.contains('<h2 class="card__title">Outcomes by day</h2></div>')
    }
}
