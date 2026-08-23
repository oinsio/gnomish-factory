package com.github.oinsio.gnomish.testsupport

import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.neverFetchedBoard
import static com.github.oinsio.gnomish.testsupport.DashboardSectionFixtures.noSweepData

import com.github.oinsio.gnomish.dashboard.DaemonSnapshotView
import com.github.oinsio.gnomish.dashboard.LedgerHistoryView

/**
 * The render call and bar-reading helpers shared by the two ledger-fed block
 * specs of redesign-dashboard, so each of them holds only the block it
 * exercises.
 *
 * FR6, FR7 of redesign-dashboard.
 */
class DashboardHistoryFixtures {

    /** Renders the whole page around {@code history}; every other section is stubbed empty. */
    static String render(LedgerHistoryView history) {
        DashboardSectionFixtures.render(new DaemonSnapshotView.Absent(), history, neverFetchedBoard(), noSweepData())
    }

    /** Every bar segment's percentage width, in document order. */
    static List<Integer> segmentWidths(String html) {
        (html =~ /class="bar__seg" style="width:(\d+)%/).collect {
            it[1] as int
        }
    }
}
