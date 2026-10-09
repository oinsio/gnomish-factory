package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.board.BoardModel;
import com.github.oinsio.gnomish.serveobservability.LifecycleState;
import com.github.oinsio.gnomish.serveobservability.Snapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Renders the status line — the page's second priority layer, directly under
 * the freshness strip: one line stating whether the daemon is running, the
 * instance it belongs to, the snapshot's own {@code writtenAt}, and the two
 * numbers an operator checks next (slot occupancy, consecutive tracker
 * failures).
 *
 * <p>This card is also where every triggered alert condition surfaces as a
 * short alarm-palette line: the operator-guide rules 1–5 from {@link
 * AlertConditionEvaluator}, and — new in this redesign — the sandbox-hygiene
 * conditions from {@link SandboxHygieneAlertEvaluator}, which used to live in
 * the hygiene section. Alerts belong where the operator already looks; the
 * hygiene block itself becomes the page's quietest reference block and
 * carries no alert styling at all.
 *
 * <p>The stats degrade per source: the slots and consecutive-failures stats
 * need a snapshot, the WIP stat needs a board model — the fresh one or the one
 * cached across a failed refresh — and renders without a snapshot too. Its
 * value is the model's own {@link BoardModel#openFrontCount()} against the
 * model's own {@link BoardModel#wipLimit()}, the limit eligibility judged the
 * WIP-held rows by, so no renderer is handed the limit separately. A full WIP
 * is the system working as configured, so the stat never takes the alarm
 * palette.
 *
 * <p>Implements FR1, FR2, FR9 of redesign-dashboard; FR12, FR13, UX3 of
 * supervise-daemon-loops-and-embed-dashboard (design D13).
 */
final class DashboardStatusCardRenderer {

    void append(
            StringBuilder out,
            DaemonSnapshotView view,
            BoardSectionView boardView,
            SandboxHygieneView hygiene,
            Instant now) {
        List<AlertCondition> flagged = new ArrayList<>(AlertConditionEvaluator.evaluate(view, now));
        flagged.addAll(SandboxHygieneAlertEvaluator.evaluate(hygiene, now));

        out.append("<div class=\"card status")
                .append(dotModifier(view))
                .append("\" id=\"status\">\n<span class=\"status__dot\"></span>\n<div class=\"status__main\">\n");
        out.append("<div class=\"status__state\">")
                .append(DashboardHtmlFormatter.escape(stateText(view)))
                .append("</div>\n");
        appendIdentity(out, view);
        for (AlertCondition condition : flagged) {
            out.append("<div class=\"status__alert\">")
                    .append(DashboardHtmlFormatter.escape(DashboardAlertLabels.label(condition)))
                    .append("</div>\n");
        }
        out.append("</div>\n");
        Snapshot snapshot = snapshotOf(view);
        if (snapshot != null) {
            appendSlotsStat(out, snapshot);
        }
        BoardModel model = boardView.model();
        if (model != null) {
            appendWipStat(out, model);
        }
        if (snapshot != null) {
            appendFailuresStat(out, snapshot);
        }
        out.append("</div>\n");
    }

    private static void appendIdentity(StringBuilder out, DaemonSnapshotView view) {
        Snapshot snapshot = snapshotOf(view);
        if (snapshot == null) {
            out.append("<div class=\"status__id\">no snapshot has been written here</div>\n");
            return;
        }
        out.append("<div class=\"status__id num\">")
                .append(DashboardHtmlFormatter.escape(snapshot.instance().instanceId()))
                .append(" &middot; snapshot: ");
        DashboardTime.append(out, snapshot.writtenAt(), null);
        out.append("</div>\n");
    }

    private static void appendSlotsStat(StringBuilder out, Snapshot snapshot) {
        int occupied = snapshot.slots().entries().size();
        appendStat(
                out,
                "slots",
                DashboardCompactNumber.format(occupied) + " / "
                        + DashboardCompactNumber.format(snapshot.slots().capacity()),
                occupied + " of " + snapshot.slots().capacity(),
                false);
    }

    /** FR12, UX3: open fronts against the model's own limit, with the split on hover; never alarm-styled. */
    private static void appendWipStat(StringBuilder out, BoardModel model) {
        int open = model.openFrontCount();
        int limit = model.wipLimit();
        appendStat(
                out,
                "WIP",
                DashboardCompactNumber.format(open) + " / " + DashboardCompactNumber.format(limit),
                open + " of " + limit + " open fronts: "
                        + model.workingRows().size() + " working, "
                        + model.awaitingHumanRows().size() + " waiting for a human",
                false);
    }

    private static void appendFailuresStat(StringBuilder out, Snapshot snapshot) {
        int failures = snapshot.tracker().consecutiveFailures();
        appendStat(
                out,
                "consecutive failures",
                DashboardCompactNumber.format(failures),
                Integer.toString(failures),
                failures > 0);
    }

    private static void appendStat(StringBuilder out, String label, String value, String exact, boolean bad) {
        out.append("<div class=\"stat\"><div class=\"stat__label\">")
                .append(DashboardHtmlFormatter.escape(label))
                .append("</div><div class=\"stat__value num")
                .append(bad ? " stat__value--bad" : "")
                .append("\" title=\"")
                .append(DashboardHtmlFormatter.escape(exact))
                .append("\">")
                .append(DashboardHtmlFormatter.escape(value))
                .append("</div></div>\n");
    }

    /** The dot's palette: alarm for a daemon that should be alive and is not, warning for a clean stop. */
    private static String dotModifier(DaemonSnapshotView view) {
        return switch (view) {
            case DaemonSnapshotView.Fresh ignored -> "";
            case DaemonSnapshotView.Stopped ignored -> " status--stopped";
            case DaemonSnapshotView.Absent ignored -> " status--down";
            case DaemonSnapshotView.DeadDaemon ignored -> " status--down";
        };
    }

    private static String stateText(DaemonSnapshotView view) {
        return switch (view) {
            case DaemonSnapshotView.Absent ignored -> "Daemon has not run here";
            case DaemonSnapshotView.Fresh ignored -> "Daemon running";
            case DaemonSnapshotView.DeadDaemon ignored -> "Snapshot not updating";
            case DaemonSnapshotView.Stopped stopped -> "Daemon stopped" + stopReason(stopped.snapshot());
        };
    }

    private static String stopReason(Snapshot snapshot) {
        return snapshot.lifecycle() instanceof LifecycleState.Stopped stopped ? " (" + stopped.reason() + ")" : "";
    }

    private static @Nullable Snapshot snapshotOf(DaemonSnapshotView view) {
        return switch (view) {
            case DaemonSnapshotView.Absent ignored -> null;
            case DaemonSnapshotView.Fresh fresh -> fresh.snapshot();
            case DaemonSnapshotView.DeadDaemon dead -> dead.snapshot();
            case DaemonSnapshotView.Stopped stopped -> stopped.snapshot();
        };
    }
}
