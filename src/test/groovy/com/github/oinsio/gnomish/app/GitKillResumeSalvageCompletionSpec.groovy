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
import com.github.oinsio.gnomish.adapter.git.ServiceCommitMessages
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
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
 * FR8, FR10, M1 of add-git-workflow: the full "kill -9 at any point -> --resume completes the
 * task" narrative on local bare repos — one full round durably committed, a simulated process
 * death mid-round (uncommitted leftovers, no round-closing persist), a fresh process resuming
 * via the same {@code --resume} code path {@link GitResumeRunner} drives, the default salvage
 * path committing the leftovers as a service commit that is NOT counted as a round in {@code
 * state.json}, and the task going on to complete a further round.
 *
 * <p>Reuses {@link BareGitRepoFixture} (task 2.1) and composes the same production adapters
 * {@link GitResumeRunnerSpec} exercises individually — this spec's contribution is the explicit
 * end-to-end chain plus the "salvage commit does not inflate state.json's attempts" assertion
 * task 6.2 calls for, which no existing spec checks directly.
 */
class GitKillResumeSalvageCompletionSpec extends Specification implements BareGitRepoFixture {

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

    private static TaskContext context(String taskId = 'PROJ-100') {
        new TaskContext(taskId, 'title', 'body', List.<Decision> of())
    }

    private static StageDefinition stage() {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage()])
    }

    private GitTaskRepository repository() {
        new GitTaskRepository(gitRunner, cloneDir, worktreesRoot)
    }

    private Path expectedWorktree(String taskId) {
        worktreesRoot.resolve('my-project').resolve(taskId)
    }

    private GitResumeRunner newResumeRunner(InputStream input, PrintStream output) {
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
    private void persistOneRound(String taskId, TaskState state) {
        def worktree = expectedWorktree(taskId)
        def persistence = new GitAttemptPersistence(gitRunner, worktree, taskId)
        def trace = new ToolTrace(new AttemptKey(taskId, 'build', 0),
                [
                    new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
                ])
        persistence.persist(taskId, state, trace)
    }

    // FR8, FR10, M1: kill -9 mid-round, then --resume salvages the leftovers into a service commit
    // that is NOT counted as a round in state.json, and the task proceeds to complete a further
    // round — the full chain the success metric describes.
    def "M1: interrupted round is salvaged (not counted as a round) then the task completes"() {
        given: 'a task with one durably committed round, as a live run would leave it'
        def taskId = 'PROJ-100'
        repository().createTask(context(taskId), null)
        persistOneRound(taskId, TaskState.atStageStart('build'))
        def worktree = expectedWorktree(taskId)
        def stateBeforeKill = StateJsonMapper.fromDto(
                StateJsonMapper.readDto(gitRunner.run(worktree, 'show', 'HEAD:.gnomish-task/state.json').stdout()))
        def branchTipAfterFirstRound = gitRunner.run(cloneDir, 'rev-parse', "gnomish/${taskId}").stdout().trim()

        and: 'the process dies mid-round: gnome work sits uncommitted, no round-closing persist ran'
        Files.writeString(worktree.resolve('half-done.txt'), 'interrupted work')
        assert gitRunner.run(worktree, 'status', '--porcelain').stdout().trim() != ''

        when: 'a fresh process resumes via the same --resume code path, salvaging by default'
        newResumeRunner(new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8')), System.out)
                .run(cloneDir, taskId, pipeline(), RunArguments.InteractiveMode.ALL, false)

        then: 'a salvage commit landed directly on top of the first round, ahead of the next round commit'
        def subjects = gitRunner.run(cloneDir, 'log', '--format=%s', "${branchTipAfterFirstRound}..gnomish/${taskId}")
                .stdout().readLines()
        subjects.contains(ServiceCommitMessages.salvage())

        and: 'the salvage commit is the very first new commit after the interrupted round'
        subjects.last() == ServiceCommitMessages.salvage()

        and: 'the salvage commit carries the leftover file'
        def salvageSha = gitRunner.run(cloneDir, 'log', '--format=%H', "${branchTipAfterFirstRound}..gnomish/${taskId}")
                .stdout().readLines().last()
        gitRunner.run(cloneDir, 'show', '--stat', salvageSha).stdout().contains('half-done.txt')

        and: 'the salvage commit is NOT a round: it carries no state.json change at all'
        gitRunner.run(cloneDir, 'show', '--stat', salvageSha).stdout().contains('.gnomish-task/state.json') == false

        and: 'the task went on to complete a further round: the branch records a Completed outcome'
        gitRunner.run(cloneDir, 'rev-parse', '--verify', "gnomish/${taskId}").exitCode() == 0
        !Files.exists(worktree)

        and: 'the completion round is a genuinely new round on top of the salvage commit, not a re-run of the first'
        def completedTaskJson = gitRunner.run(cloneDir, 'show', "gnomish/${taskId}~1:.gnomish-task/task.json").stdout()
        TaskJsonMapper.fromDto(TaskJsonMapper.readDto(completedTaskJson)).outcome().type() == 'completed'

        and: 'the round recorded by that completion is the only round.json attempt beyond the pre-kill one — the'
        and: 'salvage commit itself never appears as an AttemptRecord in any state.json on the branch'
        def finalRoundStateJson = gitRunner.run(cloneDir, 'show', "gnomish/${taskId}~2:.gnomish-task/state.json").stdout()
        def finalRoundState = StateJsonMapper.fromDto(StateJsonMapper.readDto(finalRoundStateJson))
        finalRoundState.attempts().size() == stateBeforeKill.attempts().size() + 1
    }
}
