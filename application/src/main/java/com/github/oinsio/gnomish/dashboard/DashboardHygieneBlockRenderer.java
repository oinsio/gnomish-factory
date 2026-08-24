package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.serveobservability.SweepCounts;
import com.github.oinsio.gnomish.serveobservability.SweepVital;

/**
 * Renders the sandbox-hygiene block — the page's quietest reference layer,
 * placed last: the last sweep tick's four-group breakdown over the six
 * verdict categories (cleaned, stopped, checked and untouched, skipped
 * without a verdict) and the tick's own timestamp. With no sweep data it
 * shrinks further still, to a dashed-border footnote saying the sweep has
 * not run — never a table of zeroes, which would read as a swept-and-clean
 * host.
 *
 * <p>Two things this block deliberately no longer carries. Its alert
 * conditions moved to the status card, where the operator already looks;
 * this block has no alert styling of any kind. And its per-object depth —
 * the kept-environment inventory with ages and time-to-reap, and the recent
 * stop/dispose actions table — is dropped from the page entirely: that
 * detail stays with the snapshot, the ledger, and {@code gnomish status
 * <id>}, which is where an operator who wants one object's history goes.
 *
 * <p>Implements FR2, FR9 of redesign-dashboard.
 */
final class DashboardHygieneBlockRenderer {

    void append(StringBuilder out, SandboxHygieneView hygiene) {
        SweepVital sweep = hygiene.sweep();
        if (sweep == null) {
            out.append("<div class=\"footnote\" id=\"hygiene\">")
                    .append(DashboardIcons.DASH)
                    .append("<span>Sandbox sweep has not run yet</span></div>\n");
            return;
        }
        StringBuilder meta = new StringBuilder("last tick: ");
        DashboardTime.append(meta, sweep.lastTickAt(), null);

        out.append("<section class=\"card\" id=\"hygiene\">\n");
        DashboardBlockChrome.appendHead(out, "Sandbox hygiene", meta.toString());
        SweepCounts counts = sweep.counts();
        appendGroup(out, "cleaned", (long) counts.disposedAged() + counts.disposedReconstructible());
        appendGroup(out, "stopped", counts.stoppedOrphan());
        appendGroup(out, "checked and untouched", (long) counts.checkedAlive() + counts.keptUnderThreshold());
        appendGroup(out, "skipped without verdict", counts.skippedNoVerdict());
        out.append("</section>\n");
    }

    private static void appendGroup(StringBuilder out, String label, long count) {
        out.append("<div class=\"row\"><span class=\"row__label\">")
                .append(DashboardHtmlFormatter.escape(label))
                .append("</span><span class=\"row__count num\" title=\"")
                .append(count)
                .append("\">")
                .append(DashboardCompactNumber.format(count))
                .append("</span></div>\n");
    }
}
