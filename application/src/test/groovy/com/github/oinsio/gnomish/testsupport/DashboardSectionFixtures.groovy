package com.github.oinsio.gnomish.testsupport

import com.github.oinsio.gnomish.dashboard.BoardSectionView
import com.github.oinsio.gnomish.dashboard.DaemonSnapshotView
import com.github.oinsio.gnomish.dashboard.DashboardHtmlRenderer
import com.github.oinsio.gnomish.dashboard.LedgerHistoryView
import com.github.oinsio.gnomish.dashboard.SandboxHygieneView
import java.time.Instant

/**
 * Empty/never-fetched section view fixtures shared by
 * {@code DashboardHtmlRenderer*Spec} tests: the history and board sections
 * a test doesn't care about are stubbed with these so each spec only wires
 * up the section it is actually exercising.
 */
class DashboardSectionFixtures {

    static final Instant GENERATED_AT = Instant.parse('2026-08-06T09:00:00Z')

    static LedgerHistoryView emptyHistory() {
        new LedgerHistoryView([], [:])
    }

    /**
     * Renders the whole page from the given section views. Shared by the
     * per-block fixture classes, each of which supplies its one real view
     * and stubs the rest.
     */
    static String render(DaemonSnapshotView daemon, LedgerHistoryView history, BoardSectionView board,
            SandboxHygieneView sandbox) {
        new DashboardHtmlRenderer().render(daemon, history, board, sandbox, GENERATED_AT, null)
    }

    static BoardSectionView neverFetchedBoard() {
        new BoardSectionView(null, null, null)
    }

    /**
     * The sandbox hygiene section a spec doesn't care about: neither a snapshot sweep vital nor a
     * ledger sweep action, which renders as the section's honest "no sweep data yet" state
     * (NFR-O3 of add-serve-sandbox-lifecycle).
     */
    static SandboxHygieneView noSweepData() {
        SandboxHygieneView.absent()
    }
}
