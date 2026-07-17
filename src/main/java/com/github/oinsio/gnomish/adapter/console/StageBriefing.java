package com.github.oinsio.gnomish.adapter.console;

import com.github.oinsio.gnomish.adapter.pipeline.PathSafety;
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.CheckResult;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.pipeline.ArtifactInput;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * Renders the human-readable briefing block the interactive {@code StageExecutor}
 * (task 5.2) prints before prompting the operator (FR3): task goal, input
 * artifacts, prior-attempt feedback, decisions, and the stage's control-file
 * content read from disk via the workspace root. A pure formatting helper — it
 * builds text only, it never prompts or reads console input.
 *
 * <p>Input artifacts carry no physical file path yet (proposal open question
 * Q1), so they are rendered symbolically by kind and producer id rather than by
 * reading files. The control file is read from {@code workspace.root()} when the
 * workspace is a {@link DirectoryWorkspace}; any failure to read it (missing
 * file, unreadable, or a non-directory workspace) degrades to a placeholder line
 * rather than throwing, so a briefing render failure never crashes the
 * interactive dialog.
 *
 * <p>Implements FR3 of add-manual-run.
 */
public final class StageBriefing {

    /**
     * Renders the full briefing for {@code request}: task goal, input
     * artifacts, prior-attempt feedback, decisions, and the stage's control-file
     * content, in that order.
     *
     * <p>Implements FR3 of add-manual-run.
     *
     * @param request the round's inputs, as passed to the interactive {@code
     *     StageExecutor}
     * @return the multi-section briefing text; never null
     */
    public String render(StageExecutor.Request request) {
        StringBuilder out = new StringBuilder();
        renderTaskGoal(out, request);
        renderInputArtifacts(out, request);
        renderFeedback(out, request);
        renderDecisions(out, request);
        renderControlFile(out, request);
        return out.toString();
    }

    private void renderTaskGoal(StringBuilder out, StageExecutor.Request request) {
        out.append("=== Task goal ===\n");
        out.append(request.context().title()).append('\n');
        if (!request.context().body().isEmpty()) {
            out.append(request.context().body()).append('\n');
        }
        out.append('\n');
    }

    private void renderInputArtifacts(StringBuilder out, StageExecutor.Request request) {
        out.append("=== Input artifacts ===\n");
        var inputs = request.stage().inputs();
        if (inputs.isEmpty()) {
            out.append("(none)\n");
        } else {
            for (ArtifactInput input : inputs) {
                out.append("- ").append(describe(input)).append('\n');
            }
        }
        out.append('\n');
    }

    private String describe(ArtifactInput input) {
        return switch (input) {
            case ArtifactInput.Internal internal -> "internal: produced by " + internal.producerOutputId();
            case ArtifactInput.Source ignored -> "source: arrives with the task's working copy";
        };
    }

    private void renderFeedback(StringBuilder out, StageExecutor.Request request) {
        out.append("=== Prior-attempt feedback ===\n");
        var feedback = request.feedback();
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

    private String describe(Verdict verdict) {
        return switch (verdict) {
            case Verdict.Pass ignored -> "passed";
            case Verdict.Fail fail -> "failed" + describeFindings(fail.findings());
            case Verdict.CannotVerify cannotVerify -> "cannot verify: " + cannotVerify.reason();
        };
    }

    private String describeFindings(List<Finding> findings) {
        if (findings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Finding finding : findings) {
            sb.append("\n    * ").append(finding.message());
        }
        return sb.toString();
    }

    private void renderDecisions(StringBuilder out, StageExecutor.Request request) {
        out.append("=== Decisions ===\n");
        var decisions = request.context().decisions();
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

    private void renderControlFile(StringBuilder out, StageExecutor.Request request) {
        out.append("=== Control file (")
                .append(request.stage().instructionsRef())
                .append(") ===\n");
        out.append(readControlFile(request)).append('\n');
    }

    private String readControlFile(StageExecutor.Request request) {
        if (!(request.workspace() instanceof DirectoryWorkspace directoryWorkspace)) {
            return "(control file unavailable: workspace is not a DirectoryWorkspace)";
        }
        String ref = request.stage().instructionsRef();
        PathSafety.Resolution resolution = PathSafety.resolveWithinRoot(directoryWorkspace.root(), ref);
        if (resolution instanceof PathSafety.Escapes escapes) {
            return "(control file could not be read: path escapes the workspace: " + escapes.ref() + ")";
        }
        PathSafety.Within within = (PathSafety.Within) resolution;
        try {
            return Files.readString(within.path());
        } catch (IOException e) {
            return "(control file could not be read: " + ref + ")";
        }
    }
}
