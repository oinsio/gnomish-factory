package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.DoNotMutate;
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
 * section renders "no sweep data yet" rather than a table of zeroes, so the operator can tell an
 * un-swept host from a swept-and-clean one (FR3 of add-dashboard-page's "a degraded section never
 * fails the others").
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements NFR-O3, UX1 of add-serve-sandbox-lifecycle.
 *
 * @param sweep the snapshot's last completed sweep tick, or null when the snapshot carries none
 * @param recentActions the window's stop/dispose actions, newest first, bounded; never null
 * @param actionsTotal how many actions the window held before the bound was applied
 */
public record SandboxHygieneView(@Nullable SweepVital sweep, List<SweepActionRow> recentActions, int actionsTotal) {

    public SandboxHygieneView {
        recentActions = List.copyOf(recentActions);
    }

    /** The no-data state: no sweep vital in the snapshot and no sweep line in the ledger window. */
    public static SandboxHygieneView absent() {
        return new SandboxHygieneView(null, List.of(), 0);
    }

    /**
     * Whether the section has anything at all to show.
     *
     * <p>{@code @DoNotMutate}: PIT's Gregor engine crashes its minion JVM (RUN_ERROR, not a real
     * coverage gap) on some mutations of this record's own methods via JVMTI RedefineClasses (see
     * {@link com.github.oinsio.gnomish.app.port.git.UsageTotals}'s identical exemption). Fully
     * covered by {@code DashboardSandboxHygieneSectionRendererSpec}'s "no sweep data at all"
     * scenario.
     *
     * @return true when neither the snapshot nor the ledger yielded sweep data
     */
    @DoNotMutate
    public boolean isEmpty() {
        return sweep == null && recentActions.isEmpty();
    }

    /**
     * Whether the actions table dropped rows — stated rather than implied, like the kept
     * inventory's own truncation.
     *
     * <p>{@code @DoNotMutate}: same JVMTI RedefineClasses exemption as {@link #isEmpty()} above.
     * Fully covered by {@code DashboardSandboxHygieneSectionRendererSpec}'s truncation scenario.
     *
     * @return true when the window held more actions than the table carries
     */
    @DoNotMutate
    public boolean actionsTruncated() {
        return actionsTotal > recentActions.size();
    }
}
