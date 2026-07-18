package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.adapter.briefing.BriefingSections;
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.nio.file.Path;

/**
 * Composes the round prompt for the CLI {@link
 * com.github.oinsio.gnomish.domain.engine.port.JudgeVoter}: a narrower
 * section subset than {@link ExecutorPromptBuilder}'s — task goal, decisions,
 * acceptance-criteria content, and a structured-verdict instruction — with no
 * prior-attempt feedback, input-artifacts, or control-file section. A vote
 * grades the working copy's current state, not the process that produced it;
 * telling the judge "this failed last time" would bias the verdict (design
 * D8). The {@link com.github.oinsio.gnomish.domain.engine.port.JudgeVoter#vote}
 * signature itself carries no feedback parameter, so this class has nothing
 * to accidentally wire in.
 *
 * <p>The acceptance-criteria file is read via the same {@link
 * ControlFilePreflight#read} the executor prompt builder uses for the control
 * file: an unreadable criteria file is an infrastructure failure before any
 * process spawns (FR13). {@link
 * ControlFilePreflight.UnreadableControlFileException} is left uncaught here
 * and is expected to propagate to the future {@code CliJudgeVoter.vote()},
 * which turns it into {@code CannotVerify} (task 7.3).
 *
 * <p>Implements FR8, D5, D8 of add-agent-executor.
 */
public final class JudgePromptBuilder {

    private static final String VERDICT_INSTRUCTION = """
            === Verdict ===
            Grade the working copy against the acceptance criteria above. End \
            your final message with a JSON object of the form {"passed": \
            true|false, "findings": ["...", "..."]} — optionally inside a \
            markdown code fence. "findings" lists concrete, specific reasons \
            when passed is false; leave it empty when passed is true.
            """;

    /**
     * Builds the judge round prompt for {@code check}.
     *
     * <p>Implements FR8, D5, D8 of add-agent-executor.
     *
     * @param check the judge check whose criteria file and model drive the vote
     * @param context the task's identity, goal, and human decisions
     * @param workspace the working copy being graded; must be a {@link
     *     DirectoryWorkspace} so the criteria file can be resolved
     * @return the full judge prompt text; never null
     * @throws ControlFilePreflight.UnreadableControlFileException if the
     *     check's acceptance-criteria file cannot be read — propagated
     *     uncaught (FR13)
     */
    public String build(VerifyCheck.Judge check, TaskContext context, Workspace workspace) {
        Path root = ((DirectoryWorkspace) workspace).root();
        StringBuilder out = new StringBuilder();

        BriefingSections.renderTaskGoal(out, context);
        BriefingSections.renderDecisions(out, context.decisions());
        renderAcceptanceCriteria(out, root, check);
        out.append(VERDICT_INSTRUCTION).append('\n');

        return out.toString();
    }

    private void renderAcceptanceCriteria(StringBuilder out, Path root, VerifyCheck.Judge check) {
        out.append("=== Acceptance criteria (").append(check.criteriaFile()).append(") ===\n");
        out.append(ControlFilePreflight.read(root, check.criteriaFile())).append('\n');
    }
}
