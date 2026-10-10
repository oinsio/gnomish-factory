package com.github.oinsio.gnomish.dashboard

import static com.github.oinsio.gnomish.testsupport.DaemonSnapshotFixtures.snapshot
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.emptyHistory
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.neverFetchedBoard
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.noSweepData

import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.board.AwaitingHumanRow
import com.github.oinsio.gnomish.board.BoardModel
import com.github.oinsio.gnomish.board.ReadySummary
import com.github.oinsio.gnomish.board.WorkingRow
import com.github.oinsio.gnomish.serveobservability.LifecycleState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * Verifies the status card's WIP stat (design D13): the board model's own
 * open-front count against the model's own WIP limit, with the
 * working/waiting split on hover, never alarm-styled, degrading with the
 * board rather than the snapshot.
 *
 * FR12, FR13, UX3 of supervise-daemon-loops-and-embed-dashboard.
 */
class DashboardStatusCardWipSpec extends Specification {

    private static final Instant GENERATED_AT = Instant.parse('2026-08-06T09:00:00Z')
    private static final Instant FETCHED_AT = Instant.parse('2026-08-06T08:59:30Z')

    def renderer = new DashboardHtmlRenderer()

    def "FR12, UX3: WIP stat with its split — 2 working and 3 waiting under a limit of 10 read 5 / 10"() {
        when:
        def card = statusCard(running(), new BoardSectionView(model(2, 3, 10), FETCHED_AT, null))

        then:
        card.contains('<div class="stat__label">WIP</div><div class="stat__value num" '
                + 'title="5 of 10 open fronts: 2 working, 3 waiting for a human">5 / 10</div>')
    }

    def "FR12, UX3: the WIP stat sits between the slots and the failures stats"() {
        when:
        def card = statusCard(running(), new BoardSectionView(model(1, 0, 4), FETCHED_AT, null))

        then:
        card.indexOf('>slots<') <card.indexOf('>WIP<')
        card.indexOf('>WIP<') <card.indexOf('>consecutive failures<')
    }

    def "FR12, UX3: full WIP is not an alarm — open fronts equal to the limit render in ordinary styling"() {
        when:
        def card = statusCard(running(), new BoardSectionView(model(2, 1, 3), FETCHED_AT, null))

        then:
        card.contains('title="3 of 3 open fronts: 2 working, 1 waiting for a human">3 / 3</div>')
        !card.contains('stat__value--bad')
    }

    def "FR13: no daemon yet — no snapshot shows the WIP stat without slot or failure stats"() {
        when:
        def card = statusCard(new DaemonSnapshotView.Absent(), new BoardSectionView(model(0, 2, 5), FETCHED_AT, null))

        then:
        card.contains('<div class="status__state">Daemon has not run here</div>')
        card.contains('>WIP</div>')
        card.contains('>2 / 5</div>')
        !card.contains('>slots<')
        !card.contains('>consecutive failures<')
    }

    def "FR13: board never loaded — no WIP stat and no placeholder for it"() {
        when:
        def card = statusCard(running(), neverFetchedBoard())

        then:
        !card.contains('WIP')
        !card.contains('open fronts')

        and: 'the snapshot stats keep their own degradation'
        card.contains('>slots<')
        card.contains('>consecutive failures<')
    }

    def "FR13: board never loaded and a failed refresh — still no WIP stat"() {
        when:
        def card = statusCard(running(), new BoardSectionView(null, null, 'tracker unreachable'))

        then:
        !card.contains('WIP')
    }

    def "FR13: a cached model after a failed refresh still renders the WIP stat from that model"() {
        when:
        def card = statusCard(running(), new BoardSectionView(model(1, 1, 7), FETCHED_AT, 'tracker unreachable'))

        then:
        card.contains('title="2 of 7 open fronts: 1 working, 1 waiting for a human">2 / 7</div>')
    }

    private String statusCard(DaemonSnapshotView daemon, BoardSectionView board) {
        def html = renderer.render(daemon, emptyHistory(), board, noSweepData(), GENERATED_AT, null)
        html.substring(html.indexOf('id="status"'), html.indexOf('id="attention"'))
    }

    private static DaemonSnapshotView running() {
        new DaemonSnapshotView.Fresh(snapshot(new LifecycleState.Running()))
    }

    private static BoardModel model(int working, int waiting, int wipLimit) {
        def workingRows = (1..<working + 1).collect {
            new WorkingRow(new TaskRef("w-$it"), UntrustedText.tracker("Working $it"), 'gnome-1', null)
        }
        def awaitingRows = (1..<waiting + 1).collect {
            new AwaitingHumanRow(new TaskRef("a-$it"), UntrustedText.tracker("Parked $it"), ParkReason.ESCALATION)
        }
        new BoardModel([], workingRows, awaitingRows, ReadySummary.tally([]), wipLimit, false, GENERATED_AT)
    }
}
