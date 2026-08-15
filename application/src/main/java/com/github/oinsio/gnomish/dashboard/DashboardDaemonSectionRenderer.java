package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.serveobservability.LifecycleState;
import com.github.oinsio.gnomish.serveobservability.Snapshot;
import java.time.Instant;
import java.util.List;

/**
 * Renders the dashboard page's daemon section (task 3.1): "daemon has not
 * run here" for {@link DaemonSnapshotView.Absent}, or the snapshot's
 * instance, lifecycle, slot occupancy, and tracker health for the three
 * snapshot-carrying variants, each with the snapshot's own {@code
 * writtenAt} (FR3, FR4, NFR-O1). Visual alert-condition flagging (task
 * 3.4): {@link AlertConditionEvaluator#evaluate} is run against {@code
 * view} using the page's own {@code generatedAt} as {@code now} — the
 * daemon section has no separate observation instant of its own, so it
 * reuses the one already flowing through {@link DashboardHtmlRenderer#render}.
 * A non-empty result adds the {@code daemon-alert} highlight class to the
 * section and lists each condition's {@link DashboardAlertLabels label} —
 * deliberately distinct in class name and wording ("daemon alert:") from
 * {@link DashboardStalenessBannerRenderer}'s page-staleness banner ("view is
 * stale"), so the two can never be mistaken for each other (UX3).
 *
 * <p>Implements FR3, FR4, UX3, NFR-O1 of add-dashboard-page (design D3, D6).
 */
final class DashboardDaemonSectionRenderer {

    void append(StringBuilder out, DaemonSnapshotView view, Instant now) {
        List<AlertCondition> flagged = AlertConditionEvaluator.evaluate(view, now);
        out.append("<section id=\"daemon\"")
                .append(flagged.isEmpty() ? "" : " class=\"daemon-alert\"")
                .append(">");
        out.append("<h2>Daemon</h2>\n");
        switch (view) {
            case DaemonSnapshotView.Absent ignored -> out.append("<p>daemon has not run here</p>\n");
            case DaemonSnapshotView.Fresh fresh -> appendData(out, fresh.snapshot(), "alive");
            case DaemonSnapshotView.DeadDaemon dead -> appendData(out, dead.snapshot(), "not responding");
            case DaemonSnapshotView.StoppedStale stopped -> appendData(out, stopped.snapshot(), "stopped");
        }
        appendAlerts(out, flagged);
        out.append("</section>\n");
    }

    private void appendAlerts(StringBuilder out, List<AlertCondition> flagged) {
        if (flagged.isEmpty()) {
            return;
        }
        out.append("<p class=\"daemon-alert-text\">daemon alert: ");
        for (int i = 0; i < flagged.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(DashboardHtmlFormatter.escape(DashboardAlertLabels.label(flagged.get(i))));
        }
        out.append("</p>\n");
    }

    private void appendData(StringBuilder out, Snapshot snapshot, String statusLabel) {
        out.append("<p>instance: ")
                .append(DashboardHtmlFormatter.escape(snapshot.instance().instanceId()))
                .append(" — ")
                .append(statusLabel)
                .append(lifecycleDetail(snapshot.lifecycle()))
                .append("</p>\n");
        out.append("<p>slots: ")
                .append(snapshot.slots().entries().size())
                .append('/')
                .append(snapshot.slots().capacity())
                .append(" occupied, tracker consecutiveFailures=")
                .append(snapshot.tracker().consecutiveFailures())
                .append("</p>\n");
        out.append("<p class=\"timestamp\">snapshot written at ")
                .append(snapshot.writtenAt())
                .append("</p>\n");
    }

    private String lifecycleDetail(LifecycleState lifecycle) {
        return lifecycle instanceof LifecycleState.Stopped stopped
                ? " (" + DashboardHtmlFormatter.escape(stopped.reason()) + ")"
                : "";
    }
}
