package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path

/**
 * Shared stand-in {@link StageExecutor.Request} for specs that only need a request to hand a
 * round: the stage's own shape (name, verify checks, executor settings) is not what any of
 * these specs assert on, only that a request reaches the round machinery over the caller's
 * workspace root.
 */
final class StageExecutorRequests {

    private StageExecutorRequests() {
    }

    static StageExecutor.Request request(Path workspaceDir) {
        def stage = new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        new StageExecutor.Request(
                new TaskContext('TASK-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), []),
                stage, new DirectoryWorkspace(workspaceDir), 0, [])
    }
}
