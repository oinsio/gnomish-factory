package com.github.oinsio.gnomish.dashboard

import static com.github.oinsio.gnomish.testsupport.DashboardBoardFixtures.FETCHED_AT
import static com.github.oinsio.gnomish.testsupport.DashboardBoardFixtures.GENERATED_AT
import static com.github.oinsio.gnomish.testsupport.DashboardBoardFixtures.boardModel
import static com.github.oinsio.gnomish.testsupport.DashboardBoardFixtures.emptyModel
import static com.github.oinsio.gnomish.testsupport.DashboardBoardFixtures.render
import static com.github.oinsio.gnomish.testsupport.DashboardPageMarkup.markup

import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.board.AwaitingHumanRow
import com.github.oinsio.gnomish.board.BoardModel
import com.github.oinsio.gnomish.board.ReadySummary
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Verifies the waiting-for-a-human block (tasks 3.3, 3.9 of
 * redesign-dashboard): loudest on the page when it holds anything, calmly
 * all-clear when it does not.
 *
 * FR4, UX1 of redesign-dashboard.
 */
class DashboardAttentionBlockSpec extends Specification {

    // FR4: when at least one task waits, its block is the most prominent element on the page.
    def "a parked task makes the block the loudest element"() {
        when:
        def html = render(new BoardSectionView(boardModel(), FETCHED_AT, null))

        then:
        html.contains('<section class="card card--attention" id="attention">')

        and: 'the row identifies the task and its park-reason category'
        html.contains('<div class="row" title="escalation">')
        html.contains('<span class="num">task-3</span>')
        html.contains('<span class="row__label">Parked title</span>')

        and: 'the count travels in the block meta'
        html.contains('&middot; <span class="num">1</span>')
    }

    // FR10: the first row's hairline is dropped by the stylesheet keying on the head it
    //       follows. A :first-of-type rule cannot do it — the head is the card's first
    //       element of its type — so the markup adjacency and the selector are pinned together.
    def "the first row sits directly after the block head, which is what the stylesheet keys on"() {
        when:
        def html = render(new BoardSectionView(boardModel(), FETCHED_AT, null))

        then:
        markup(html).contains('</div>\n<div class="row" title="escalation">')

        and: 'the stylesheet drops the hairline by adjacency, not by a rule that never matches'
        html.contains('.card__head + .row { border-top: none; }')
        !html.contains('.row:first-of-type')
    }

    // FR4: the glyph distinguishes the park reason category before the row's text is read.
    @Unroll
    def "a #reason row carries its own park-reason glyph and label"() {
        given:
        def rows = [
            new AwaitingHumanRow(new TaskRef('task-7'), 'Parked title', reason)
        ]
        def model = new BoardModel([], [], rows, ReadySummary.tally([]), false, GENERATED_AT)

        when:
        def html = render(new BoardSectionView(model, FETCHED_AT, null))

        then:
        html.contains('<div class="row" title="' + label + '">'
                + '<svg class="row__icon" viewBox="0 0 16 16" aria-hidden="true">' + glyph)

        where:
        reason | label | glyph
        ParkReason.ESCALATION | 'escalation' | '<circle cx="8" cy="8" r="6"'
        ParkReason.CHECKPOINT | 'checkpoint' | '<rect x="4.4"'
        ParkReason.INFRA | 'infra' | '<path d="M9.2 1.8'
    }

    // FR4, Q1: the port exposes no escalation reason text and no escalation instant today, so the
    //     row drops both rather than filling them with a placeholder.
    def "fields the tracker does not expose are dropped, never placeheld"() {
        when:
        def html = render(new BoardSectionView(boardModel(), FETCHED_AT, null))

        then:
        !markup(html).contains('row__reason')
        !html.contains('n/a')
        !html.contains('&mdash;')
    }

    // UX1: an empty queue is the good outcome and must read that way.
    def "an empty queue keeps its block and reads as a deliberate all-clear"() {
        when:
        def html = render(new BoardSectionView(emptyModel(), FETCHED_AT, null))

        then:
        html.contains('id="attention"')
        !markup(html).contains('card--attention')
        html.contains('class="empty empty--ok"')
        html.contains('The queue is empty — the gnomes are managing on their own')
        html.contains('&middot; <span class="num">0</span>')
    }
}
