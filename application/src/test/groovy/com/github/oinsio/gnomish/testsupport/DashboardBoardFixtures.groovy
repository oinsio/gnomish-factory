package com.github.oinsio.gnomish.testsupport

import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.emptyHistory
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.noSweepData

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.board.AwaitingHumanRow
import com.github.oinsio.gnomish.board.BoardModel
import com.github.oinsio.gnomish.board.ReadyRow
import com.github.oinsio.gnomish.board.ReadySummary
import com.github.oinsio.gnomish.board.WorkingRow
import com.github.oinsio.gnomish.dashboard.BoardSectionView
import com.github.oinsio.gnomish.dashboard.DaemonSnapshotView
import java.time.Instant

/**
 * The board-fed fixtures and the one-line render call shared by the three
 * board-block specs of redesign-dashboard, so each of them holds only the
 * block it exercises.
 *
 * FR2, FR4, FR5 of redesign-dashboard.
 */
class DashboardBoardFixtures {

    static final Instant GENERATED_AT = DashboardSectionFixtures.GENERATED_AT
    static final Instant FETCHED_AT = Instant.parse('2026-08-06T08:59:30Z')

    /** Renders the whole page around {@code boardView}; every other section is stubbed empty. */
    static String render(BoardSectionView boardView) {
        DashboardSectionFixtures.render(new DaemonSnapshotView.Absent(), emptyHistory(), boardView, noSweepData())
    }

    static BoardModel emptyModel() {
        new BoardModel([], [], [], ReadySummary.tally([]), false, GENERATED_AT)
    }

    /** One row in each of the three lists — the shape most block assertions need. */
    static BoardModel boardModel() {
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

    /** A board holding one working row only, for the claim-marker cases. */
    static BoardModel workingOnly(String holder, ClaimVersion claim) {
        new BoardModel([], [
            new WorkingRow(new TaskRef('task-2'), 'Working title', holder, claim)
        ],
        [], ReadySummary.tally([]), false, GENERATED_AT)
    }
}
