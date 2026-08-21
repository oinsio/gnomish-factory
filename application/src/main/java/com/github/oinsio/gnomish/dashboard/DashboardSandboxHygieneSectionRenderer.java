package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.serveobservability.SweepCounts;
import com.github.oinsio.gnomish.serveobservability.SweepVital;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Renders the dashboard page's sandbox hygiene section (NFR-O3, UX1 of
 * add-serve-sandbox-lifecycle): the last tick's breakdown in the four groups UX1 asks for —
 * cleaned, stopped, checked-and-untouched, skipped-without-verdict — mapped over the six verdict
 * categories, the kept-environment inventory with time-to-reap, and the recent stop/dispose
 * actions read from the ledger.
 *
 * <p>Degrades independently, like every other section (FR3 of add-dashboard-page): a snapshot
 * without a sweep vital, an unreadable ledger, or both, render as "no sweep data yet" — never an
 * exception, never a table of zeroes that would read as a swept-and-clean host. The two halves
 * degrade separately too: a fresh daemon shows its breakdown with an empty actions table, and a
 * snapshot lost to an older build still shows the ledger's actions.
 *
 * <p>Alert flagging follows {@link DashboardDaemonSectionRenderer}'s established shape — the
 * section gains a highlight class and lists each condition's {@link DashboardAlertLabels label} —
 * but keys off {@link SandboxHygieneAlertEvaluator} and carries its OWN class and wording
 * ({@code sandbox-alert}, "sandbox alert:"), so a stalled sweep never masquerades as a daemon
 * alert, as the page-staleness banner, or the reverse (UX2; UX3 of add-dashboard-page).
 *
 * <p>Implements NFR-O3, UX1, UX2 of add-serve-sandbox-lifecycle.
 */
final class DashboardSandboxHygieneSectionRenderer {

    void append(StringBuilder out, SandboxHygieneView hygiene, Instant now) {
        List<AlertCondition> flagged = SandboxHygieneAlertEvaluator.evaluate(hygiene, now);
        out.append("<section id=\"sandbox-hygiene\"")
                .append(flagged.isEmpty() ? "" : " class=\"sandbox-alert\"")
                .append("><h2>Sandbox hygiene</h2>\n");
        if (hygiene.isEmpty()) {
            out.append("<p>no sweep data yet</p>\n");
        } else {
            appendBreakdown(out, hygiene.sweep());
            appendKeptInventory(out, hygiene.sweep());
            appendActions(out, hygiene);
        }
        appendAlerts(out, flagged);
        out.append("</section>\n");
    }

    private void appendBreakdown(StringBuilder out, @Nullable SweepVital sweep) {
        if (sweep == null) {
            out.append("<p>last tick: no snapshot vital</p>\n");
            return;
        }
        SweepCounts counts = sweep.counts();
        out.append("<table><tr><th>cleaned</th><th>stopped</th><th>checked and untouched</th>"
                        + "<th>skipped without verdict</th></tr>\n<tr><td>")
                .append(counts.disposedAged() + counts.disposedReconstructible())
                .append("</td><td>")
                .append(counts.stoppedOrphan())
                .append("</td><td>")
                .append(counts.checkedAlive() + counts.keptUnderThreshold())
                .append("</td><td>")
                .append(counts.skippedNoVerdict())
                .append("</td></tr>\n</table>\n");
        out.append("<p class=\"timestamp\">last sweep tick at ")
                .append(DashboardHtmlFormatter.escape(sweep.lastTickAt().toString()))
                .append("</p>\n");
    }

    private void appendKeptInventory(StringBuilder out, @Nullable SweepVital sweep) {
        if (sweep == null) {
            return;
        }
        if (sweep.kept().isEmpty()) {
            out.append("<p>no kept environments</p>\n");
            return;
        }
        out.append("<table><tr><th>kept task</th><th>age</th><th>time to reap</th></tr>\n");
        sweep.kept()
                .forEach(entry -> out.append("<tr><td>")
                        .append(DashboardHtmlFormatter.escape(entry.taskKey()))
                        .append("</td><td>")
                        .append(DashboardDurationFormatter.format(entry.ageSeconds()))
                        .append("</td><td>")
                        .append(DashboardDurationFormatter.format(entry.untilReapSeconds()))
                        .append("</td></tr>\n"));
        out.append("</table>\n");
        if (sweep.keptTruncated()) {
            out.append("<p class=\"timestamp\">showing ")
                    .append(sweep.kept().size())
                    .append(" of ")
                    .append(sweep.keptTotal())
                    .append(" kept environments</p>\n");
        }
    }

    private void appendActions(StringBuilder out, SandboxHygieneView hygiene) {
        if (hygiene.recentActions().isEmpty()) {
            out.append("<p>no recent sweep actions</p>\n");
            return;
        }
        out.append("<table><tr><th>at</th><th>object</th><th>role</th><th>mode</th><th>task</th>"
                + "<th>action</th><th>reason</th><th>age</th></tr>\n");
        hygiene.recentActions().forEach(row -> appendActionRow(out, row));
        out.append("</table>\n");
        if (hygiene.actionsTruncated()) {
            out.append("<p class=\"timestamp\">showing ")
                    .append(hygiene.recentActions().size())
                    .append(" of ")
                    .append(hygiene.actionsTotal())
                    .append(" sweep actions in the window</p>\n");
        }
    }

    private static void appendActionRow(StringBuilder out, SweepActionRow row) {
        out.append("<tr><td>")
                .append(DashboardHtmlFormatter.escape(row.at().toString()))
                .append("</td><td>")
                .append(DashboardHtmlFormatter.escape(row.objectName()))
                .append("</td><td>")
                .append(DashboardHtmlFormatter.escape(row.role()))
                .append("</td><td>")
                .append(DashboardHtmlFormatter.escape(row.mode()))
                .append("</td><td>")
                .append(DashboardHtmlFormatter.escape(row.taskKey()))
                .append("</td><td>")
                .append(DashboardHtmlFormatter.escape(DashboardSweepLabels.action(row.category())))
                .append("</td><td>")
                .append(DashboardHtmlFormatter.escape(row.reason()))
                .append("</td><td>")
                .append(row.ageSeconds() == null ? "&mdash;" : DashboardDurationFormatter.format(row.ageSeconds()))
                .append("</td></tr>\n");
    }

    private void appendAlerts(StringBuilder out, List<AlertCondition> flagged) {
        if (flagged.isEmpty()) {
            return;
        }
        out.append("<p class=\"sandbox-alert-text\">sandbox alert: ");
        for (int i = 0; i < flagged.size(); i++) {
            if (i > 0) {
                out.append("; ");
            }
            out.append(DashboardHtmlFormatter.escape(DashboardAlertLabels.label(flagged.get(i))));
        }
        out.append("</p>\n");
    }
}
