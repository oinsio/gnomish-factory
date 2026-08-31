package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import org.slf4j.MDC
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Common fixture shared by {@link GitResumeSpecBase} and {@link TakeResumeSpecBase}: a
 * bare-repo-backed clone with an initial commit, a {@link GitProcessRunner}, and the
 * builder/helper methods both resume-runner families need to create tasks, build pipeline
 * definitions, and persist rounds. Spock composes {@code setup()}/{@code cleanup()} across the
 * inheritance hierarchy automatically, so subclasses may add their own without calling super.
 *
 * <p>Implements FR5, FR8, FR9, FR12, UX2, D3 of add-git-workflow / add-tracker-port.
 */
abstract class ResumeSpecFixtureBase extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @TempDir
    Path tempDir

    Path cloneDir
    Path worktreesRoot
    def gitRunner = new GitProcessRunner()

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'my-project')
        Files.writeString(cloneDir.resolve('instructions.md'), 'build it\n')
        commitAll(cloneDir)
        worktreesRoot = tempDir.resolve('worktrees-root')
    }

    def cleanup() {
        MDC.remove('taskId')
    }

    protected static TaskContext context(String taskId = 'PROJ-1') {
        new TaskContext(taskId, 'title', 'body', List.<Decision> of())
    }

    protected GitTaskRepository repository() {
        new GitTaskRepository(gitRunner, cloneDir, worktreesRoot, ClaimEpochSource.NONE)
    }

    protected Path expectedWorktree(String taskDir) {
        worktreesRoot.resolve('my-project').resolve(taskDir)
    }

    protected static StageDefinition stage(AdvancementMode mode = AdvancementMode.AUTO) {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), mode)
    }

    protected static PipelineDefinition pipeline(AdvancementMode mode = AdvancementMode.AUTO) {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage(mode)])
    }

    /** Persists one real round via GitAttemptPersistence so state.json exists, as a live task would. */
    protected void persistOneRound(String taskId, TaskState state) {
        def worktree = expectedWorktree(taskId)
        def persistence = new GitAttemptPersistence(gitRunner, worktree, taskId, ClaimEpochSource.NONE)
        def trace = new ToolTrace(new AttemptKey(taskId, 'build', 0),
                [
                    new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
                ])
        persistence.persist(taskId, state, trace)
    }
}
