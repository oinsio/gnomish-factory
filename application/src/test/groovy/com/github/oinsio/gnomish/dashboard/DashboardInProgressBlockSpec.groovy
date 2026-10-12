package com.github.oinsio.gnomish.dashboard

import static com.github.oinsio.gnomish.testsupport.DashboardBoardFixtures.FETCHED_AT
import static com.github.oinsio.gnomish.testsupport.DashboardBoardFixtures.GENERATED_AT
import static com.github.oinsio.gnomish.testsupport.DashboardBoardFixtures.emptyModel
import static com.github.oinsio.gnomish.testsupport.DashboardBoardFixtures.render
import static com.github.oinsio.gnomish.testsupport.DashboardBoardFixtures.workingOnly
import static com.github.oinsio.gnomish.testsupport.DashboardPageMarkup.markup

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.board.BoardLabels
import com.github.oinsio.gnomish.board.BoardModel
import com.github.oinsio.gnomish.board.EligibilityReason
import com.github.oinsio.gnomish.board.ReadyRow
import com.github.oinsio.gnomish.board.ReadySummary
import com.github.oinsio.gnomish.board.WorkingRow
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.serveobservability.SlotEntry
import com.github.oinsio.gnomish.serveobservability.SlotsSnapshot
import com.github.oinsio.gnomish.serveobservability.Snapshot
import com.github.oinsio.gnomish.testsupport.AlertSnapshotFixtures
import com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
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
    // Scenario: observability/dashboard-page / Ready and working share one list
    // Scenario: observability/dashboard-page / Backoff annotation matches the board command
    def "working and ready rows share one list with distinct dots and trailing notes"() {
        given:
        def workingRows = [
            new WorkingRow(new TaskRef('task-2'), UntrustedText.tracker('Working title'), 'gnome-1',
            new ClaimVersion('m-1', GENERATED_AT.minusSeconds(180), new ClaimEpoch(1)))
        ]
        def backoff = new EligibilityReason.InBackoff(Instant.parse('2026-08-06T10:00:00Z'))
        def readyRows = [
            new ReadyRow(new TaskRef('task-1'), UntrustedText.tracker('Ready title'), false, backoff)
        ]
        def model = new BoardModel(readyRows, workingRows, [], ReadySummary.tally(readyRows), 3, false, GENERATED_AT)

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
            new ReadyRow(new TaskRef('task-1'), UntrustedText.tracker('Ready title'), false, null)
        ]
        def model = new BoardModel(readyRows, [], [], ReadySummary.tally(readyRows), 3, false, GENERATED_AT)

        when:
        def html = render(new BoardSectionView(model, FETCHED_AT, null))

        then:
        html.contains('<span class="row__label">Ready title</span></div>')
    }

    // Scenario: observability/dashboard-page / Capped ready window stays visible
    def "a capped ready window still says more is ready than fits"() {
        given:
        def readyRows = [
            new ReadyRow(new TaskRef('task-1'), UntrustedText.tracker('Ready title'), false, null)
        ]
        def model = new BoardModel(readyRows, [], [], ReadySummary.tally(readyRows), 3, true, GENERATED_AT)

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

    // FR5 of redesign-dashboard: the rows take the board's entry fields — a working row its holder
    //     and claim age, the ready rows the board's queue order and each its own eligibility note.
    // Scenario: observability/dashboard-page / In-progress rows carry the board's entry fields
    def "working rows lead with holder and claim age, ready rows follow in queue order with their notes"() {
        given: 'a working task, then three ready tasks in the order the board queued them'
        def claim = new ClaimVersion('m-1', GENERATED_AT.minusSeconds(180), new ClaimEpoch(1))
        def working = [
            new WorkingRow(new TaskRef('task-w'), UntrustedText.tracker('Working title'), 'gnome-1', claim)
        ]
        def backoff = new EligibilityReason.InBackoff(Instant.parse('2026-08-06T10:00:00Z'))
        def ready = [
            ['task-c', backoff],
            [
                'task-a',
                new EligibilityReason.Finished()
            ],
            [
                'task-b',
                new EligibilityReason.WipHeld()
            ],
        ].collect { id, reason ->
            new ReadyRow(new TaskRef(id as String), UntrustedText.tracker("title of ${id}" as String), false, reason as EligibilityReason)
        }
        def model = new BoardModel(ready, working, [], ReadySummary.tally(ready), 3, false, GENERATED_AT)

        when:
        def rows = (markup(render(new BoardSectionView(model, FETCHED_AT, null))) =~ /<div class="row">(.*?)<\/div>/)
                .collect { it[1] }

        then: 'the working row first, with its holder and its claim age as a time element'
        rows[0].contains('task-w')
        rows[0].contains('<span class="row__age">gnome-1 · updated <time datetime="2026-08-06T08:57:00Z"')

        and: 'the ready rows next, in the queue order, each with its short note'
        rows[1..3].collect {
            (it =~ /<span class="num">([^<]*)</)[0][1]
        } == ['task-c', 'task-a', 'task-b']
        rows[1].contains('<span class="row__age">in backoff until 2026-08-06T10:00:00Z</span>')
        rows[2].contains('<span class="row__age">finished</span>')
        rows[3].contains('<span class="row__age">WIP-held</span>')
    }

    // FR10 of add-dashboard-page: a row is a pointer — the task id to hand to `gnomish status <id>`
    //     and the tracker — never the slot's stage, its artifacts or its log.
    // Scenario: observability/dashboard-page / Slot entry stays a pointer
    def "a task held in a slot is shown by its id and title alone, with no stage detail, artifact or link"() {
        given: 'a running daemon holding task-2 in a slot at stage implement, and the board row for it'
        def base = AlertSnapshotFixtures.healthySnapshot()
        def slots = new SlotsSnapshot(3, [
            new SlotEntry('task-2', 'implement', 2, GENERATED_AT)
        ])
        def snapshot = new Snapshot(base.version(), base.writtenAt(), base.intervalSeconds(), base.instance(),
                base.lifecycle(), base.feed(), slots, base.vitals(), base.tracker(), [:])
        def model = workingOnly('gnome-1', null)

        when:
        def html = markup(DashboardSectionFixtures.render(new DaemonSnapshotView.Fresh(snapshot),
                DashboardSectionFixtures.emptyHistory(), new BoardSectionView(model, FETCHED_AT, null),
                DashboardSectionFixtures.noSweepData()))

        then: 'the row offers the id to look the task up by'
        html.contains('<span class="num">task-2</span><span class="row__label">Working title</span>')

        and: "nothing of the slot's work reaches the page: no stage, no attempt, no artifact, log or link"
        !html.contains('implement')
        !(html =~ /(?i)<a\b|href=|\.log\b|\.gnomish\/|stage\.yaml/).find()
    }
}
