package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.CommandLine
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck

/**
 * The mixed pipeline the host and container command-stage flow specs of add-command-executor run
 * (task 7.1): a {@code build} stage of type {@code command} whose declared command is flaky by
 * construction, verified by a {@code files_exist} check on its result, then a {@code review} stage
 * of type {@code agent-cli}. The command is the manifest's own data — a {@code sh -c} string, as a
 * stage manifest declares it — not a generated executable: on a working copy without {@link #MARKER}
 * it writes the marker, prints {@link #OUTPUT} and exits 1; on one that holds it (a retry in the same
 * working copy) it writes {@link #RESULT} and exits 0.
 */
final class CommandStagePipeline {

    private CommandStagePipeline() {
    }

    /** The file the failing first round leaves in the working copy. */
    static final String MARKER = 'flaky.marker'

    /** The file the passing second round writes, which the stage's verify check requires. */
    static final String RESULT = 'build-result.txt'

    /** What the failing round prints: the tail its finding quotes. */
    static final String OUTPUT = 'first build broke'

    /** The stage's declared command, run as {@code sh -c} in the working copy. */
    static final String COMMAND = "if [ -f ${MARKER} ]; then echo built > ${RESULT}; " +
    "else echo seen > ${MARKER}; echo '${OUTPUT}'; exit 1; fi"

    /** The finding the failing round reports, as the built-in words it (design D4). */
    static final String FINDING = "command '${COMMAND}' failed with exit code 1; its output ended with:\n${OUTPUT}"

    /** The check names an executor-reported failure records: the executor's own label, never a verify check. */
    static final List<String> EXECUTOR_CHECK = ['executor:build']

    /** The check names the verify chain records: its one built-in {@code files_exist} check. */
    static final List<String> VERIFY_CHECK = ['builtin:files_exist']

    static PipelineDefinition law() {
        def build = new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.COMMAND, '', [:], Sandbox.none(), null, new CommandLine(COMMAND)),
        'instructions.md', [
            new VerifyCheck.Builtin('files_exist', [files: [RESULT]])
        ], new AutonomyLimits(3),
        AdvancementMode.AUTO)
        def review = new StageDefinition('review', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
        'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(3), [build, review])
    }

    static List<StageDefinition> stages() {
        law().stages()
    }
}
