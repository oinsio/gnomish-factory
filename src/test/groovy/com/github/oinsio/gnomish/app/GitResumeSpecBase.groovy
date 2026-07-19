package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.console.SystemConsoleIO
import com.github.oinsio.gnomish.adapter.engine.SystemClock
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository
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
 * Shared fixture for the resume specs — bootstrap (task 4.6) and outcome-driven continuation
 * (task 4.7): a bare-repo-backed clone with an initial commit, a {@link GitProcessRunner}, and
 * the builder/helper methods both {@link GitResumeBootstrapSpec} and {@link GitResumeOutcomeSpec}
 * need to create tasks, drive a {@link GitResumeRunner}, and persist rounds. Implements FR5, FR8,
 * UX2 of add-git-workflow.
 */
abstract class GitResumeSpecBase extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    Path cloneDir
    Path worktreesRoot
    def gitRunner = new GitProcessRunner()

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'my-project')
        Files.writeString(cloneDir.resolve('instructions.md'), 'build it\n')
        gitRunner.run(cloneDir, 'add', 'instructions.md')
        gitRunner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        worktreesRoot = tempDir.resolve('worktrees-root')
    }

    def cleanup() {
        MDC.remove('taskId')
    }

    protected static TaskContext context(String taskId = 'PROJ-1') {
        new TaskContext(taskId, 'title', 'body', List.<Decision> of())
    }

    protected GitTaskRepository repository() {
        new GitTaskRepository(gitRunner, cloneDir, worktreesRoot)
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

    protected GitResumeRunner newResumeRunner(InputStream input, PrintStream output) {
        def assembly = new ManualRunAssembly(
                new SystemConsoleIO(input, output),
                new FilesExistCheckRunner(),
                new ShellCommandCheckRunner(),
                new SystemClock(),
                new ThreadSleeper(),
                new FactoryProperties('test-instance', null, null))
        new GitResumeRunner(assembly, worktreesRoot, 'taskId')
    }

    /** Persists one real round via GitAttemptPersistence so state.json exists, as a live task would. */
    protected void persistOneRound(String taskId, TaskState state) {
        def worktree = expectedWorktree(taskId)
        def persistence = new GitAttemptPersistence(gitRunner, worktree, taskId)
        def trace = new ToolTrace(new AttemptKey(taskId, 'build', 0),
                [
                    new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
                ])
        persistence.persist(taskId, state, trace)
    }

    /** Rewrites task.json's outcome field to a Completed marker, without running FR15 cleanup. */
    protected void writeCompletedTaskJson(String taskId) {
        def worktree = expectedWorktree(taskId)
        def taskJson = worktree.resolve('.gnomish-task').resolve('task.json')
        def rewritten = Files.readString(taskJson)
                .replaceFirst(/"outcome"\s*:\s*null/, '"outcome":{"type":"completed"}')
        Files.writeString(taskJson, rewritten)
        gitRunner.run(worktree, 'add', '-A')
        gitRunner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'mark completed')
    }
}
