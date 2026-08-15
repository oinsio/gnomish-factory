package com.github.oinsio.gnomish.adapter.briefing;

import com.github.oinsio.gnomish.domain.engine.CheckResult;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.domain.pipeline.ArtifactInput;
import java.util.List;

/**
 * Composable, pure-formatting briefing sections (FR14, D8 of
 * add-agent-executor): each method renders exactly one section into a shared
 * {@link StringBuilder}, taking only the pre-read data it needs — no coupling
 * to {@code StageExecutor.Request} or to file I/O. Callers pick and stitch
 * the subset they need (the interactive executor uses all five via {@link
 * #renderExecutorBriefing}; the judge, task 6/7, will use a different
 * subset — goal, decisions, criteria, verdict instruction — without prior-
 * attempt feedback or input artifacts).
 *
 * <p>Implements FR14 of add-agent-executor; the logic itself carries forward
 * FR3 of add-manual-run, moved here unchanged from {@code adapter.console}.
 */
public final class BriefingSections {

    private BriefingSections() {}

    /**
     * Renders the interactive/executor briefing exactly as {@code
     * StageBriefing} did before extraction: task goal, input artifacts,
     * prior-attempt feedback, decisions, and the control-file section, in
     * that order. A convenience for the common five-section case; callers
     * needing a different subset (e.g. the judge) compose the individual
     * {@code render*} methods directly instead.
     *
     * <p>Implements FR14 of add-agent-executor.
     *
     * @param out the buffer to append into
     * @param context the task context (goal, decisions)
     * @param inputs the stage's declared input artifacts
     * @param feedback prior-attempt check results, empty on the first attempt
     * @param instructionsRef the control-file reference, for the section header
     * @param controlFileContent the control file's content, or a caller-chosen
     *     placeholder string when it could not be read
     */
    public static void renderExecutorBriefing(
            StringBuilder out,
            TaskContext context,
            List<ArtifactInput> inputs,
            List<CheckResult> feedback,
            String instructionsRef,
            String controlFileContent) {
        renderTaskGoal(out, context);
        renderInputArtifacts(out, inputs);
        renderFeedback(out, feedback);
        renderDecisions(out, context.decisions());
        renderControlFile(out, instructionsRef, controlFileContent);
    }

    /** Renders the task-goal section: title and, when non-empty, body. */
    public static void renderTaskGoal(StringBuilder out, TaskContext context) {
        out.append("=== Task goal ===\n");
        out.append(context.title()).append('\n');
        if (!context.body().isEmpty()) {
            out.append(context.body()).append('\n');
        }
        out.append('\n');
    }

    /** Renders the input-artifacts section, symbolically by kind and producer id. */
    public static void renderInputArtifacts(StringBuilder out, List<ArtifactInput> inputs) {
        out.append("=== Input artifacts ===\n");
        if (inputs.isEmpty()) {
            out.append("(none)\n");
        } else {
            for (ArtifactInput input : inputs) {
                out.append("- ").append(describe(input)).append('\n');
            }
        }
        out.append('\n');
    }

    private static String describe(ArtifactInput input) {
        return switch (input) {
            case ArtifactInput.Internal internal -> "internal: produced by " + internal.producerOutputId();
            case ArtifactInput.Source ignored -> "source: arrives with the task's working copy";
        };
    }

    /** Renders the prior-attempt-feedback section from the previous round's check results. */
    public static void renderFeedback(StringBuilder out, List<CheckResult> feedback) {
        out.append("=== Prior-attempt feedback ===\n");
        if (feedback.isEmpty()) {
            out.append("(none)\n");
        } else {
            for (CheckResult result : feedback) {
                out.append("- ")
                        .append(result.checkRef().label())
                        .append(": ")
                        .append(describe(result.verdict()))
                        .append('\n');
            }
        }
        out.append('\n');
    }

    private static String describe(Verdict verdict) {
        return switch (verdict) {
            case Verdict.Pass ignored -> "passed";
            case Verdict.Fail fail -> "failed" + describeFindings(fail.findings());
            case Verdict.CannotVerify cannotVerify -> "cannot verify: " + cannotVerify.reason();
        };
    }

    private static String describeFindings(List<Finding> findings) {
        if (findings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Finding finding : findings) {
            sb.append("\n    * ").append(finding.message());
        }
        return sb.toString();
    }

    /** Renders the decisions section: author (or "unattributed") and body per decision. */
    public static void renderDecisions(StringBuilder out, List<Decision> decisions) {
        out.append("=== Decisions ===\n");
        if (decisions.isEmpty()) {
            out.append("(none)\n");
        } else {
            for (Decision decision : decisions) {
                String author = decision.author() != null ? decision.author() : "unattributed";
                out.append("- ")
                        .append(author)
                        .append(": ")
                        .append(decision.body())
                        .append('\n');
            }
        }
        out.append('\n');
    }

    /**
     * Renders the control-file section under its {@code === Control file (ref)
     * ===} header. {@code content} is whatever the caller decided to hand in —
     * the real file text, or a placeholder string of the caller's own wording
     * when the file could not be read; this method renders it verbatim and has
     * no opinion on placeholder policy (D8 of add-agent-executor: reading and
     * failure-reaction stay with each adapter).
     */
    public static void renderControlFile(StringBuilder out, String instructionsRef, String content) {
        out.append("=== Control file (").append(instructionsRef).append(") ===\n");
        out.append(content).append('\n');
    }
}
