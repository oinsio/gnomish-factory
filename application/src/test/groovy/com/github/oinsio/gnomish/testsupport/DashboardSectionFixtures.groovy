package com.github.oinsio.gnomish.testsupport

import com.github.oinsio.gnomish.dashboard.BoardSectionView
import com.github.oinsio.gnomish.dashboard.LedgerHistoryView

/**
 * Empty/never-fetched section view fixtures shared by
 * {@code DashboardHtmlRenderer*Spec} tests: the history and board sections
 * a test doesn't care about are stubbed with these so each spec only wires
 * up the section it is actually exercising.
 */
class DashboardSectionFixtures {

    static LedgerHistoryView emptyHistory() {
        new LedgerHistoryView([], [:])
    }

    static BoardSectionView neverFetchedBoard() {
        new BoardSectionView(null, null, null)
    }
}
