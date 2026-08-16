package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.domain.engine.AttemptRecord;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import org.jspecify.annotations.Nullable;

/**
 * Renders a {@link StatusReport} as human-readable English text (task 6.4 of
 * add-manual-run): {@link #renderFull(StatusReport)} produces a multi-line block
 * for the {@code status} meta-command and the runner's after-attempt/final
 * summaries; {@link #renderAttemptSummary(AttemptRecord)} produces a single line
 * per finished attempt. Kept as a separate class from {@link StatusReport}
 * (design D7 "one pure model behind every render"): the model stays plain data,
 * renders live beside it. Individual line formatting is delegated to {@link
 * StatusLineFormatter}, split out to keep both files within the project's
 * file-size guidance.
 *
 * <p>Implements FR10, UX2, D7 of add-manual-run.
 */
public final class StatusTextRenderer {

    /**
     * Renders {@code report} as a readable multi-line block: task id/title,
     * current stage (or "pipeline complete"), attempts used/limit, a summary line
     * per attempt, decisions, cumulative totals, current activity (if any), last
     * escalation (if any), and last decision (if any).
     *
     * <p>Implements FR10, UX2, D7 of add-manual-run.
     *
     * @param report the report to render; never null
     * @return the rendered text block, ready to print verbatim
     */
    public String renderFull(StatusReport report) {
        StringBuilder out = new StringBuilder();
        out.append("Task: ")
                .append(report.taskId())
                .append(" — ")
                .append(report.title())
                .append('\n');
        appendStage(out, report);
        appendAttempts(out, report);
        appendDecisions(out, report);
        appendTotals(out, report.totals());
        appendActivity(out, report.activity());
        appendEscalation(out, report.lastEscalation());
        appendLastDecision(out, report.lastDecision());
        return out.toString();
    }

    /**
     * Renders a single line summarizing one finished attempt: its round number,
     * result classification, and a verdict/duration highlight.
     *
     * <p>Implements FR10, UX2, D7 of add-manual-run.
     *
     * @param record the attempt to summarize; never null
     * @return one line, no trailing newline
     */
    public String renderAttemptSummary(AttemptRecord record) {
        return "Round " + record.round() + ": " + StatusLineFormatter.resultLabel(record.result()) + " ("
                + StatusLineFormatter.checkHighlight(record) + ")";
    }

    private void appendStage(StringBuilder out, StatusReport report) {
        if (report.currentStage() == null) {
            out.append("Stage: pipeline complete\n");
            return;
        }
        out.append("Stage: ").append(report.currentStage());
        if (report.attemptLimit() != null) {
            out.append(" (attempt ")
                    .append(report.attemptsUsed())
                    .append('/')
                    .append(report.attemptLimit())
                    .append(')');
        }
        out.append('\n');
    }

    private void appendAttempts(StringBuilder out, StatusReport report) {
        if (report.attempts().isEmpty()) {
            return;
        }
        out.append("Attempts:\n");
        for (AttemptRecord attempt : report.attempts()) {
            out.append("  ").append(renderAttemptSummary(attempt)).append('\n');
        }
    }

    private void appendDecisions(StringBuilder out, StatusReport report) {
        if (report.decisions().isEmpty()) {
            return;
        }
        out.append("Decisions:\n");
        for (Decision decision : report.decisions()) {
            out.append("  - ")
                    .append(StatusLineFormatter.decisionLine(decision))
                    .append('\n');
        }
    }

    private void appendTotals(StringBuilder out, ExecutorUsage totals) {
        out.append("Totals: wallMillis=")
                .append(
                        totals.wallTime() == null
                                ? "unknown"
                                : totals.wallTime().toMillis())
                .append(", tokensByModel=")
                .append(totals.tokensByModel().isEmpty() ? "unknown" : totals.tokensByModel())
                .append('\n');
    }

    private void appendActivity(StringBuilder out, @Nullable Activity activity) {
        if (activity == null) {
            return;
        }
        out.append("Activity: ")
                .append(StatusLineFormatter.activityLine(activity))
                .append('\n');
    }

    private void appendEscalation(StringBuilder out, @Nullable EscalationReport escalation) {
        if (escalation == null) {
            return;
        }
        out.append("Last escalation: ")
                .append(StatusLineFormatter.escalationLine(escalation))
                .append('\n');
    }

    private void appendLastDecision(StringBuilder out, @Nullable Decision decision) {
        if (decision == null) {
            return;
        }
        out.append("Last decision: ")
                .append(StatusLineFormatter.decisionLine(decision))
                .append('\n');
    }
}
