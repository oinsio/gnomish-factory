package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.domain.engine.AttemptRecord;
import com.github.oinsio.gnomish.domain.engine.CheckResult;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.Verdict;
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

    static String activityLine(Activity activity) {
        return switch (activity) {
            case Activity.Executing executing -> executingLine(executing);
            case Activity.Verifying verifying ->
                "verifying " + verifying.checkRef().label() + " (since " + verifying.since() + ")";
            case Activity.AwaitingInput awaitingInput ->
                "awaiting input: \"" + awaitingInput.prompt() + "\" (since " + awaitingInput.since() + ")";
        };
    }

    // FR7, D10, D12 of add-agent-executor: appends live tool detail when present
    private static String executingLine(Activity.Executing executing) {
        String base = "executing (since " + executing.since() + ")";
        if (executing.currentTool() == null && executing.toolCalls() == 0) {
            return base;
        }
        return base + " [tool: " + executing.currentTool() + ", toolCalls: " + executing.toolCalls() + "]";
    }

    static String escalationLine(EscalationReport escalation) {
        return switch (escalation) {
            case EscalationReport.AttemptsExhausted exhausted -> "attempts exhausted (limit " + exhausted.limit() + ")";
            case EscalationReport.DecisionNeeded decisionNeeded -> "decision needed: " + decisionNeeded.question();
            case EscalationReport.CannotVerify cannotVerify ->
                "cannot verify " + cannotVerify.check().label() + ": " + cannotVerify.reason();
            case EscalationReport.PipelineMismatch pipelineMismatch ->
                "pipeline mismatch: stage \"" + pipelineMismatch.staleStage() + "\" not found";
            case EscalationReport.CannotExecute cannotExecute -> "cannot execute: " + cannotExecute.cause();
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
