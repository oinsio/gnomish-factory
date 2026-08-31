package com.github.oinsio.gnomish.dashboard

import static com.github.oinsio.gnomish.testsupport.DashboardBoardFixtures.FETCHED_AT
import static com.github.oinsio.gnomish.testsupport.DashboardBoardFixtures.GENERATED_AT
import static com.github.oinsio.gnomish.testsupport.DashboardBoardFixtures.emptyModel
import static com.github.oinsio.gnomish.testsupport.DashboardBoardFixtures.render
import static com.github.oinsio.gnomish.testsupport.DashboardBoardFixtures.workingOnly

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.board.BoardLabels
import com.github.oinsio.gnomish.board.BoardModel
import com.github.oinsio.gnomish.board.EligibilityReason
import com.github.oinsio.gnomish.board.ReadyRow
import com.github.oinsio.gnomish.board.ReadySummary
import com.github.oinsio.gnomish.board.WorkingRow
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.time.Instant
import spock.lang.Specification

/**
 * Verifies the in-progress block (tasks 3.4, 3.9 of redesign-dashboard):
 * one compact row list over Ready and Working, distinguishable without
 * separate headings.
 *
 * FR5, FR8 of redesign-dashboard.
 */
class DashboardInProgressBlockSpec extends Specification {

    // FR5: Ready and Working share one list, distinguishable without separate headings.
    def "working and ready rows share one list with distinct dots and trailing notes"() {
        given:
        def workingRows = [
            new WorkingRow(new TaskRef('task-2'), 'Working title', 'gnome-1',
            new ClaimVersion('m-1', GENERATED_AT.minusSeconds(180), new ClaimEpoch(1)))
        ]
        def backoff = new EligibilityReason.InBackoff(Instant.parse('2026-08-06T10:00:00Z'))
        def readyRows = [
            new ReadyRow(new TaskRef('task-1'), 'Ready title', false, backoff)
        ]
        def model = new BoardModel(readyRows, workingRows, [], ReadySummary.tally(readyRows), false, GENERATED_AT)

        when:
        def html = render(new BoardSectionView(model, FETCHED_AT, null))

        then: 'the working row carries the holder and the same claim age the CLI board reports'
        html.contains('<span class="row__dot"></span><span class="num">task-2</span>')
        html.contains('<span class="row__age">gnome-1 · updated <time datetime="2026-08-06T08:57:00Z" ' +
                'data-epoch="' + GENERATED_AT.minusSeconds(180).toEpochMilli() + '">2026-08-06T08:57:00Z</time></span>')

        and: 'the ready row is marked and carries the same backoff annotation the CLI board formats'
        html.contains('<span class="row__dot row__dot--ready"></span><span class="num">task-1</span>')
        html.contains(DashboardHtmlFormatter.escape(BoardLabels.eligibilityAnnotation(backoff)))
        html.contains('in backoff until ')

        and: 'no sub-headings split the one list'
        !html.contains('<h3>')
    }

    // FR8: a missing claim marker carries no instant, so the row keeps the board's own words.
    def "a working row without a claim marker says so instead of rendering a time"() {
        when:
        def html = render(new BoardSectionView(workingOnly('gnome-1', null), FETCHED_AT, null))

        then:
        html.contains('<span class="row__age">gnome-1 · freshness unknown</span>')
        !html.contains('<span class="row__age">gnome-1 · updated <time')
    }

    def "an eligible ready row carries no note rather than an empty one"() {
        given:
        def readyRows = [
            new ReadyRow(new TaskRef('task-1'), 'Ready title', false, null)
        ]
        def model = new BoardModel(readyRows, [], [], ReadySummary.tally(readyRows), false, GENERATED_AT)

        when:
        def html = render(new BoardSectionView(model, FETCHED_AT, null))

        then:
        html.contains('<span class="row__label">Ready title</span></div>')
    }

    def "a capped ready window still says more is ready than fits"() {
        given:
        def readyRows = [
            new ReadyRow(new TaskRef('task-1'), 'Ready title', false, null)
        ]
        def model = new BoardModel(readyRows, [], [], ReadySummary.tally(readyRows), true, GENERATED_AT)

        when:
        def html = render(new BoardSectionView(model, FETCHED_AT, null))

        then:
        html.contains('truncated: showing first 1 only')
    }

    def "one empty state covers both an idle slot and an empty ready queue"() {
        when:
        def html = render(new BoardSectionView(emptyModel(), FETCHED_AT, null))

        then:
        html.contains('A slot is free, no ready tasks in the tracker')
    }
}
