package com.github.oinsio.gnomish.adapter.agent.command

import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.CommandLine
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path

/**
 * What the command built-in's specs build alike: a {@code command} stage declaring {@code command}
 * with {@code settings} and {@code checks}, and one round of it. Nothing here runs a process.
 */
final class CommandFixtures {

    static final String COMMAND = 'make build'

    private CommandFixtures() {
    }

    static StageDefinition stage(Map<String, Object> settings = [:], String command = COMMAND, List<VerifyCheck> checks = []) {
        new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.COMMAND, '', settings, Sandbox.none(), null,
        command == null ? null : new CommandLine(command)),
        'instructions.md', checks, new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    static StageExecutor.Request round(StageDefinition stage = stage(), Path workspace = Path.of('.')) {
        new StageExecutor.Request(
                new TaskContext('TASK-7', UntrustedText.tracker('title'), UntrustedText.tracker('body'), []),
                stage, new DirectoryWorkspace(workspace), 0, [])
    }
}
