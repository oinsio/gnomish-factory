package com.github.oinsio.gnomish.dashboard

import static com.github.oinsio.gnomish.testsupport.DaemonSnapshotFixtures.snapshot
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.emptyHistory
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.neverFetchedBoard

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.board.AwaitingHumanRow
import com.github.oinsio.gnomish.board.BoardModel
import com.github.oinsio.gnomish.board.EligibilityReason
import com.github.oinsio.gnomish.board.ReadyRow
import com.github.oinsio.gnomish.board.ReadySummary
import com.github.oinsio.gnomish.board.WorkingRow
import com.github.oinsio.gnomish.serveobservability.LedgerTokenUsage
import com.github.oinsio.gnomish.serveobservability.LifecycleState
import com.github.oinsio.gnomish.serveobservability.OutcomeCounts
import java.time.Instant
import java.time.LocalDate
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Verifies {@link DashboardHtmlRenderer} composes the three dashboard
 * sections into one self-contained HTML page (task 3.1): each section
 * renders its happy-path data with its own timestamp, degrades
 * independently to the FR3-specified placeholder text, and the page shows
 * its own {@code generatedAt}.
 *
 * FR2, FR3, FR10, NFR-O1 of add-dashboard-page (design D6).
 */
class DashboardHtmlRendererSpec extends Specification {

    def renderer = new DashboardHtmlRenderer()

    private static final Instant GENERATED_AT = Instant.parse('2026-08-06T09:00:00Z')
    private static final Instant WRITTEN_AT = Instant.parse('2026-08-06T08:59:00Z')

    def "the page shows its own generatedAt"() {
        when:
        def html = renderer.render(new DaemonSnapshotView.Absent(), emptyHistory(), neverFetchedBoard(), GENERATED_AT, null)

        then:
        html.contains(GENERATED_AT.toString())
    }

    @Unroll
    def "daemon section: #description"() {
        when:
        def html = renderer.render(view, emptyHistory(), neverFetchedBoard(), GENERATED_AT, null)

        then:
        html.contains(expectedText)

        where:
        description | view | expectedText
        'no snapshot ever written' | new DaemonSnapshotView.Absent() | 'daemon has not run here'
        'fresh snapshot shows writtenAt' | new DaemonSnapshotView.Fresh(snapshot(new LifecycleState.Running())) | WRITTEN_AT.toString()
        'dead-daemon snapshot still shows its instance data' | new DaemonSnapshotView.DeadDaemon(snapshot(new LifecycleState.Running())) | WRITTEN_AT.toString()
        'stopped-stale shows the stop reason' | new DaemonSnapshotView.StoppedStale(snapshot(new LifecycleState.Stopped('sigterm'))) | 'sigterm'
    }

    def "history section: empty ledger renders no placeholder error, just an empty section"() {
        when:
        def html = renderer.render(new DaemonSnapshotView.Absent(), emptyHistory(), neverFetchedBoard(), GENERATED_AT, null)

        then:
        html.contains('History')
        !html.toLowerCase().contains('error')
    }

    def "history section: happy path shows per-day counts and its own day range"() {
        given:
        def history = new LedgerHistoryView(
                [
                    new DayOutcomeCounts(LocalDate.parse('2026-08-05'), new OutcomeCounts(3, 1, 0, 0))
                ],
                [claude: new LedgerTokenUsage(10, 5, 0, 0)])

        when:
        def html = renderer.render(new DaemonSnapshotView.Absent(), history, neverFetchedBoard(), GENERATED_AT, null)

        then:
        html.contains('2026-08-05')
        html.contains('claude')

        and: 'the outcome table itself renders the per-day counts, not just the day-range summary'
        html.contains('<td>3</td><td>1</td><td>0</td><td>0</td>')

        and: 'each history row carries an inline CSS bar so volume reads at a glance (FR6)'
        html.contains('class="bar"')
    }

    def "history section: the tokens-by-model table surfaces cache tokens, not just input/output (FR6)"() {
        given: 'a model whose ledger records non-zero cache-creation and cache-read counts'
        def history = new LedgerHistoryView(
                [
                    new DayOutcomeCounts(LocalDate.parse('2026-08-05'), new OutcomeCounts(1, 0, 0, 0))
                ],
                [claude: new LedgerTokenUsage(10, 5, 7, 3)])

        when:
        def html = renderer.render(new DaemonSnapshotView.Absent(), history, neverFetchedBoard(), GENERATED_AT, null)

        then: 'the cache columns the ledger writes for cost accounting are rendered, not dropped'
        html.contains('<th>cacheCreation</th>')
        html.contains('<th>cacheRead</th>')

        and: 'the model row carries all four counts in order'
        html.contains('<td>10</td><td>5</td><td>7</td><td>3</td>')
    }

    def "board section: unavailable when never fetched and the tracker failed"() {
        given:
        def board = new BoardSectionView(null, null, 'tracker unreachable: connection refused')

        when:
        def html = renderer.render(new DaemonSnapshotView.Absent(), emptyHistory(), board, GENERATED_AT, null)

        then:
        html.contains('unavailable')
        html.contains('connection refused')
    }

    def "board section: cached model shown with its fetch time and a refresh-failure notice"() {
        given:
        def fetchedAt = Instant.parse('2026-08-06T08:00:00Z')
        def board = new BoardSectionView(boardModel(), fetchedAt, 'tracker unreachable: timeout')

        when:
        def html = renderer.render(new DaemonSnapshotView.Absent(), emptyHistory(), board, GENERATED_AT, null)

        then:
        html.contains(fetchedAt.toString())
        html.contains('timeout')
        html.contains('task-1')
    }

    def "board section: an ineligible Ready row shows its eligibility annotation text"() {
        given:
        def deadline = Instant.parse('2026-08-06T10:00:00Z')
        def readyRows = [
            new ReadyRow(new TaskRef('task-4'), 'Backed-off title', false, new EligibilityReason.InBackoff(deadline))
        ]
        def model = new BoardModel(readyRows, [], [], ReadySummary.tally(readyRows), false, GENERATED_AT)
        def board = new BoardSectionView(model, GENERATED_AT, null)

        when:
        def html = renderer.render(new DaemonSnapshotView.Absent(), emptyHistory(), board, GENERATED_AT, null)

        then:
        html.contains('in backoff until ' + deadline)
    }

    def "board section: happy path shows Ready, Working, and AwaitingHuman rows as pointers"() {
        given:
        def fetchedAt = Instant.parse('2026-08-06T08:59:30Z')
        def board = new BoardSectionView(boardModel(), fetchedAt, null)

        when:
        def html = renderer.render(new DaemonSnapshotView.Absent(), emptyHistory(), board, GENERATED_AT, null)

        then:
        html.contains(fetchedAt.toString())
        html.contains('task-1')
        html.contains('Ready title')
        html.contains('task-2')
        html.contains('gnome-1')
        html.contains('task-3')
        html.contains('escalation')
        !html.contains('refresh-failure')

        and: 'the Working row carries its claim freshness, not just the holder (FR5)'
        html.contains('freshness unknown')
    }

    // FR5: a Working row shows the same claim-age label the CLI board renders, not just the holder.
    def "board section: a Working row shows its claim age when the marker is present"() {
        given:
        def workingRows = [
            new WorkingRow(new TaskRef('task-2'), 'Working title', 'gnome-1',
            new ClaimVersion('m-1', GENERATED_AT.minusSeconds(180)))
        ]
        def model = new BoardModel([], workingRows, [], ReadySummary.tally([]), false, GENERATED_AT)
        def board = new BoardSectionView(model, GENERATED_AT, null)

        when:
        def html = renderer.render(new DaemonSnapshotView.Absent(), emptyHistory(), board, GENERATED_AT, null)

        then:
        html.contains('holder=gnome-1, updated 3m ago')
    }

    // FR5: a capped Ready window carries the truncation marker, as the CLI board shows it.
    def "board section: a truncated Ready window shows the truncation marker"() {
        given:
        def readyRows = [
            new ReadyRow(new TaskRef('task-1'), 'Ready title', false, null)
        ]
        def model = new BoardModel(readyRows, [], [], ReadySummary.tally(readyRows), true, GENERATED_AT)
        def board = new BoardSectionView(model, GENERATED_AT, null)

        when:
        def html = renderer.render(new DaemonSnapshotView.Absent(), emptyHistory(), board, GENERATED_AT, null)

        then:
        html.contains('truncated: showing first 1 only')
    }

    private static BoardModel boardModel() {
        def readyRows = [
            new ReadyRow(new TaskRef('task-1'), 'Ready title', false, null)
        ]
        def workingRows = [
            new WorkingRow(new TaskRef('task-2'), 'Working title', 'gnome-1', null)
        ]
        def awaitingRows = [
            new AwaitingHumanRow(new TaskRef('task-3'), 'Parked title', ParkReason.ESCALATION)
        ]
        new BoardModel(readyRows, workingRows, awaitingRows, ReadySummary.tally(readyRows), false, GENERATED_AT)
    }
}
