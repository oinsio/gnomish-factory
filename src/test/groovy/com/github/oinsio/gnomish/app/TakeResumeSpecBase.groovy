package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.AbortHandler
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.slf4j.MDC
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Shared fixture for {@link TakeResumeRunner} specs (task 5.6): a bare-repo-backed clone, a
 * {@link GitProcessRunner}, and the builder/helper methods needed to create tasks, drive a
 * {@link TakeResumeRunner}, and persist rounds — mirroring {@code GitResumeSpecBase} for the
 * manual-run resume machinery this class reuses.
 *
 * <p>Implements FR9, FR12, D3 of add-tracker-port.
 */
abstract class TakeResumeSpecBase extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    protected static final TaskRef REF = new TaskRef('PROJ-1')
    protected static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')
    protected static final int ABORT_THRESHOLD = 3

    @TempDir
    Path tempDir

    Path cloneDir
    Path worktreesRoot
    def gitRunner = new GitProcessRunner()
    Tracker tracker = Mock()

    /**
     * The claim holder {@link #tracker}'s {@code fetchTask} stub reports (defaults to this
     * instance's own id, i.e. "still ours and alive"). A test can flip this before the round that
     * should be observed as revoked runs, without needing a second, order-dependent {@code >>}
     * stub competing with this one.
     */
    protected String workingHolder = INSTANCE.value()

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'my-project')
        Files.writeString(cloneDir.resolve('instructions.md'), 'build it\n')
        gitRunner.run(cloneDir, 'add', 'instructions.md')
        gitRunner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        worktreesRoot = tempDir.resolve('worktrees-root')

        tracker.fetchTask(_) >> {
            new TrackerTask(
            REF, new TaskSnapshot('PROJ-1', 'title', 'body'),
            new TrackerTaskState.Working(workingHolder), AbortFacts.none())
        }
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

    protected TakeResumeRunner newTakeResumeRunner(
            InputStream input = new ByteArrayInputStream((System.lineSeparator() * 20).getBytes('UTF-8')),
            FactoryProperties factoryProperties = testProperties(),
            List<String> credentialEnvVarsToScrub = [],
            ClaimLossFlag claimLossFlag = new ClaimLossFlag()) {
        def assembly = newAssembly(input, System.out, factoryProperties)
        def abortHandler = new AbortHandler(tracker, Clock.systemUTC())
        new TakeResumeRunner(
                assembly, worktreesRoot, 'taskId', abortHandler, ABORT_THRESHOLD, credentialEnvVarsToScrub, claimLossFlag)
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
}
