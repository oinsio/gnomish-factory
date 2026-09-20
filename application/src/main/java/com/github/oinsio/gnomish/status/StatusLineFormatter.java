package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.domain.engine.AttemptRecord;
import com.github.oinsio.gnomish.domain.engine.CheckResult;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Duration;

/**
 * Package-private line-formatting helpers for {@link StatusTextRenderer}: one
 * English line per domain concept (an activity, an escalation, a decision, an
 * attempt's result). Split out from {@link StatusTextRenderer} to keep both
 * files under the project's file-size guidance — the renderer assembles the
 * full-report block, this class formats the individual lines it assembles from.
 *
 * <p>Implements FR10, UX2, D7 of add-manual-run.
 */
final class StatusLineFormatter {

    private StatusLineFormatter() {}

    static String activityLine(Activity activity, ReportPlane plane) {
        return switch (activity) {
            case Activity.Executing executing -> executingLine(executing, plane);
            case Activity.Verifying verifying ->
                "verifying " + plane.render(verifying.checkRef().label()) + " (since " + verifying.since() + ")";
            case Activity.AwaitingInput awaitingInput ->
                "awaiting input: \"" + plane.render(awaitingInput.prompt()) + "\" (since " + awaitingInput.since()
                        + ")";
        };
    }

    // FR7, D10, D12 of add-agent-executor: appends live tool detail when present
    private static String executingLine(Activity.Executing executing, ReportPlane plane) {
        String base = "executing (since " + executing.since() + ")";
        UntrustedText tool = executing.currentTool();
        if (tool == null && executing.toolCalls() == 0) {
            return base;
        }
        return base + " [tool: " + (tool == null ? "null" : plane.render(tool)) + ", toolCalls: "
                + executing.toolCalls() + "]";
    }

    static String escalationLine(EscalationReport escalation, ReportPlane plane) {
        return switch (escalation) {
            case EscalationReport.AttemptsExhausted exhausted -> "attempts exhausted (limit " + exhausted.limit() + ")";
            case EscalationReport.DecisionNeeded decisionNeeded ->
                "decision needed: " + plane.render(decisionNeeded.question());
            case EscalationReport.CannotVerify cannotVerify ->
                "cannot verify " + plane.render(cannotVerify.check().label()) + ": "
                        + plane.render(cannotVerify.reason());
            case EscalationReport.PipelineMismatch pipelineMismatch ->
                "pipeline mismatch: stage \"" + plane.render(pipelineMismatch.staleStage()) + "\" not found";
            case EscalationReport.CannotExecute cannotExecute ->
                "cannot execute: " + plane.render(cannotExecute.cause());
        };
    }

    static String decisionLine(Decision decision) {
        StringBuilder line = new StringBuilder(decision.body());
        if (decision.author() != null) {
            line.append(" (by ").append(decision.author()).append(')');
        }
        if (decision.stage() != null) {
            line.append(" [stage: ").append(decision.stage()).append(']');
        }
        return line.toString();
    }

    /**
     * One finding as an English line — the message, and the locator when the finding
     * carries one. Used for an attempt's egress denials, whose location is the denied
     * destination and path; details (the request kind and method) stay out of the line
     * to keep the block scannable, and are carried in full by the JSON contract.
     *
     * <p>Implements UX1 of fix-denial-report-attachment.
     */
    static String findingLine(Finding finding, ReportPlane plane) {
        String message = oneLine(finding.message(), plane);
        if (finding.location() == null) {
            return message;
        }
        return message + " (" + oneLine(finding.location(), plane) + ")";
    }

    /**
     * Neutralizes finding text for the plane the report is bound for (FR15 of add-sandbox-core): a
     * denial's host and path are chosen by the gnome, so the text is untrusted and passes the
     * findings funnel's choke point — ANSI and control sequences stripped, every line break
     * rendered as a visible escape, length capped. One finding must stay one line, or a crafted
     * path forges report rows of its own; the cap keeps a hostile locator from owning the console.
     * A report bound for the tracker additionally has its mentions broken, so a crafted path cannot
     * ping a team ({@link ReportPlane#COMMENT}). Findings as data are untouched: {@code state.json}
     * still carries them verbatim.
     *
     * <p>Was a hand-rolled {@code strip(...)}-plus-flatten pair before FR6 of
     * harden-logging-observability gave the rule one owner — this is that owner's first in-place
     * consumer.
     */
    private static String oneLine(String text, ReportPlane plane) {
        return plane.line(text);
    }

    static String resultLabel(AttemptRecord.Result result) {
        return switch (result) {
            case PASSED -> "passed";
            case QUALITY_FAILURE -> "quality failure";
            case CANNOT_VERIFY -> "cannot verify";
            case DECISION_NEEDED -> "decision needed";
        };
    }

    static String checkHighlight(AttemptRecord record) {
        if (record.checkResults().isEmpty()) {
            return "no checks";
        }
        long failed = record.checkResults().stream()
                .filter(check -> !(check.verdict() instanceof Verdict.Pass))
                .count();
        String verdictSummary = failed == 0
                ? record.checkResults().size() + " checks passed"
                : failed + "/" + record.checkResults().size() + " checks failed";
        return verdictSummary + ", " + totalMillis(record) + "ms";
    }

    private static long totalMillis(AttemptRecord record) {
        return record.checkResults().stream()
                .map(CheckResult::duration)
                .mapToLong(Duration::toMillis)
                .sum();
    }
}
