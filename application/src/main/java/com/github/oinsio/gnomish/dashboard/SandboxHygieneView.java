package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.serveobservability.SweepVital;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The dashboard page's sandbox-hygiene view model (NFR-O3, UX1 of add-serve-sandbox-lifecycle):
 * the snapshot's last sweep tick, if the snapshot carries one, plus the recent stop/dispose
 * actions read from the ledger window.
 *
 * <p>Both halves degrade on their own, and {@link #absent()} is the state where neither exists —
 * a snapshot written before this change, or a daemon whose first tick has not completed. The
 * hygiene block renders "the sweep has not run yet" rather than a table of zeroes, so the operator
 * can tell an un-swept host from a swept-and-clean one (FR3 of add-dashboard-page's "a degraded
 * section never fails the others").
 *
 * <p>Since redesign-dashboard the page itself shows only the sweep half: {@code recentActions}
 * stays because {@link SandboxHygieneAlertEvaluator} reads it for the dead-instance conditions the
 * status card raises (FR9 of redesign-dashboard).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements NFR-O3, UX1 of add-serve-sandbox-lifecycle.
 *
 * @param sweep the snapshot's last completed sweep tick, or null when the snapshot carries none
 * @param recentActions the window's stop/dispose actions, newest first, bounded; never null
 */
public record SandboxHygieneView(@Nullable SweepVital sweep, List<SweepActionRow> recentActions) {

    public SandboxHygieneView {
        recentActions = List.copyOf(recentActions);
    }

    /** The no-data state: no sweep vital in the snapshot and no sweep line in the ledger window. */
    public static SandboxHygieneView absent() {
        return new SandboxHygieneView(null, List.of());
    }
}
