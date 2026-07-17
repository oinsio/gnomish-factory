package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.adapter.briefing.BriefingSections;
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.nio.file.Path;
import java.util.List;

/**
 * Composes the full round prompt for the CLI {@code StageExecutor} (task 6.5
 * assembles it into {@code execute()}): the shared briefing sections, the
 * executor epilogue — the stage's full verify plan with judge acceptance
 * criteria embedded, and the decision-file instruction — and, on retries
 * only, a rework preamble (design D9(a)).
 *
 * <p>This class owns the control-file and judge-criteria-file reads itself,
 * both via the same {@link ControlFilePreflight#read} used by {@link
 * com.github.oinsio.gnomish.adapter.console.StageBriefing}'s CLI-side sibling:
 * an unreadable file is this adapter's specific failure reaction — an
 * infrastructure failure before any process spawns (FR13) — distinct from the
 * interactive adapter's placeholder degradation. {@link
 * ControlFilePreflight.UnreadableControlFileException} is left uncaught here
 * and is expected to propagate to the future {@code execute()}, which turns
 * it into a "cannot execute" outcome without burning a stage attempt.
 *
 * <p>Implements FR2, FR13, D8, D9 of add-agent-executor.
 */
public final class ExecutorPromptBuilder {

    private static final String DECISION_FILE_INSTRUCTION = """
            === Asking a human ===
            If you need a human decision before you can proceed, write a JSON \
            object of the form {"question": "...", "options": ["...", "..."]} \
            to the path in the $GNOMISH_DECISION_FILE environment variable and \
            finish your turn. Leave that file untouched if you do not need to ask \
            anything.
            """;

    private static final String REWORK_PREAMBLE = """
            === Rework, not restart ===
            This working copy already contains the result of the prior attempt. \
            Rework it in place using the feedback above — do not discard or \
            restart the prior work.
            """;

    /**
     * Builds the round prompt for {@code request}.
     *
     * <p>Implements FR2, FR13, D8, D9 of add-agent-executor.
     *
     * @param request the round's inputs: task context, stage, workspace,
     *     attempt number and prior-attempt feedback
     * @return the full prompt text; never null
     * @throws ControlFilePreflight.UnreadableControlFileException if the
     *     stage's control file, or a judge check's acceptance-criteria file,
     *     cannot be read — propagated uncaught (FR13)
     */
    public String build(StageExecutor.Request request) {
        Path root = ((DirectoryWorkspace) request.workspace()).root();
        StringBuilder out = new StringBuilder();

        BriefingSections.renderExecutorBriefing(
                out,
                request.context(),
                request.stage().inputs(),
                request.feedback(),
                request.stage().instructionsRef(),
                ControlFilePreflight.read(root, request.stage().instructionsRef()));

        renderVerifyPlan(out, root, request.stage().verify());
        out.append(DECISION_FILE_INSTRUCTION).append('\n');
        if (request.attempt() > 0) {
            out.append(REWORK_PREAMBLE).append('\n');
        }
        return out.toString();
    }

    private void renderVerifyPlan(StringBuilder out, Path root, List<VerifyCheck> checks) {
        out.append("=== Verify plan ===\n");
        if (checks.isEmpty()) {
            out.append("(none)\n");
        } else {
            for (VerifyCheck check : checks) {
                out.append("- ").append(describe(root, check)).append('\n');
            }
        }
        out.append('\n');
    }

    private String describe(Path root, VerifyCheck check) {
        return switch (check) {
            case VerifyCheck.Builtin builtin -> "builtin: " + builtin.name() + " " + builtin.params();
            case VerifyCheck.Command command -> "command: " + command.command();
            case VerifyCheck.External external -> "external: " + external.checkId();
            case VerifyCheck.Judge judge -> describeJudge(root, judge);
        };
    }

    private String describeJudge(Path root, VerifyCheck.Judge judge) {
        String criteria = ControlFilePreflight.read(root, judge.criteriaFile());
        return "judge: " + judge.criteriaFile() + " (model " + judge.model() + ")\n  Acceptance criteria:\n  "
                + criteria.replace("\n", "\n  ");
    }
}
