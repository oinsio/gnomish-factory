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
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
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
 * FR5, FR8, FR10, D1, D10 of add-git-workflow: two {@link GitResumeContinuation} scenarios not
 * covered elsewhere in the suite without them being masked by later cleanup. Split out from {@link
 * GitResumeRunnerSpec} to keep both files within the file-size guidance
 * (.claude/rules/process-invariants.md):
 *
 * <ul>
 *   <li>{@link GitResumeContinuation#recordDecisionIfAppended} only appends a {@link Decision}
 *       through {@link GitTaskRepository#appendDecision} when the escalation dialog actually grew
 *       the context's decision list — a blank (bare Enter) answer resumes without one (FR9 of
 *       add-manual-run). PIT: ConditionalsBoundaryMutator on the {@code after > before} comparison
 *       — this is the one scenario in the suite where no decision is appended at all.
 *   <li>{@link GitResumeContinuation#resumeFromRecordedPosition}'s {@code --discard-work} path
 *       calls {@link com.github.oinsio.gnomish.adapter.git.WorktreeSalvage#discard}. {@link
 *       GitResumeRunnerSpec}'s own discard-work scenario checks the leftover file's absence only
 *       from the live worktree — but a completed task's worktree is unconditionally removed by
 *       {@link GitOutcomeRecorder} regardless of whether discard ran, so that check alone cannot
 *       distinguish the mutant (PIT: VoidMethodCallMutator survivor). This spec instead proves it
 *       through the branch's own commit history, which a removed worktree does not erase.
 * </ul>
 */
class GitResumeContinuationEdgeCasesSpec extends Specification implements BareGitRepoFixture {

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

    private static TaskContext context(String taskId = 'PROJ-1') {
        new TaskContext(taskId, 'title', 'body', List.<Decision> of())
    }

    private GitTaskRepository repository() {
        new GitTaskRepository(gitRunner, cloneDir, worktreesRoot)
    }

    private Path expectedWorktree(String taskDir) {
        worktreesRoot.resolve('my-project').resolve(taskDir)
    }

    private static PipelineDefinition pipeline() {
        def stage = new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(3), [stage])
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

    private void persistOneRound(String taskId, TaskState state) {
        def worktree = expectedWorktree(taskId)
        def persistence = new GitAttemptPersistence(gitRunner, worktree, taskId)
        def trace = new ToolTrace(new AttemptKey(taskId, 'build', 0),
                [
                    new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
                ])
        persistence.persist(taskId, state, trace)
    }

    // FR5, FR8, D1, PIT ConditionalsBoundaryMutator: a blank decision answer must NOT append a
    // Decision — proven by the historical task.json blobs never carrying a second decision entry,
    // unlike GitResumeRunnerSpec's "drives the decision dialog" scenario where a non-blank answer
    // does land one.
    def "run() with outcome escalated and a blank decision answer resumes without appending a decision"() {
        given: 'a task escalated after one persisted round'
        def taskId = 'PROJ-40'
        repository().createTask(context(taskId), null)
        def afterRound = TaskState.atStageStart('build')
        persistOneRound(taskId, afterRound)
        def report = new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])
        repository().recordOutcome(taskId, new TaskOutcome.Escalated(afterRound, report))

        and: 'stdin supplies a blank answer (bare Enter) for the decision prompt, then another for the resumed round'
        def script = System.lineSeparator() + System.lineSeparator()
        def out = new ByteArrayOutputStream()

        when:
        newResumeRunner(new ByteArrayInputStream(script.getBytes('UTF-8')), new PrintStream(out, true, 'UTF-8'))
                .run(cloneDir, taskId, pipeline(), RunArguments.InteractiveMode.ALL, false)

        then: 'the task still reaches completion'
        gitRunner.run(cloneDir, 'rev-parse', '--verify', "gnomish/${taskId}").exitCode() == 0
        !Files.exists(expectedWorktree(taskId))

        and: 'no decision was ever appended — every historical task.json still carries an empty decisions list'
        def historicalTaskJsons = gitRunner.run(cloneDir, 'log', "gnomish/${taskId}", '--format=%H').stdout()
                .lines().collect { gitRunner.run(cloneDir, 'show', "${it}:.gnomish-task/task.json") }
                .findAll { it.exitCode() == 0 }
                .collect { it.stdout() }
        historicalTaskJsons.every { !it.contains('"decisions":[{') }
    }

    // FR10, D10, PIT VoidMethodCallMutator: --discard-work must call WorktreeSalvage#discard —
    // proven through the branch's own commit history (never erased by the later worktree-removal
    // cleanup on completion, unlike GitResumeRunnerSpec's live-worktree check), so a leftover file
    // that discard() should have wiped before the round commit never appears in ANY commit's tree.
    def "run() with --discard-work never commits the discarded leftover to branch history"() {
        given: 'a task with one persisted round, then leftovers from a process that died mid-round'
        def taskId = 'PROJ-41'
        repository().createTask(context(taskId), null)
        persistOneRound(taskId, TaskState.atStageStart('build'))
        def worktree = expectedWorktree(taskId)
        Files.writeString(worktree.resolve('half-done.txt'), 'interrupted work')

        when: 'resuming with --discard-work drives the task to completion'
        newResumeRunner(new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8')), System.out)
                .run(cloneDir, taskId, pipeline(), RunArguments.InteractiveMode.ALL, true)

        then: 'half-done.txt never appears in any commit on the branch, even though the worktree itself was later removed'
        def allBlobPaths = gitRunner.run(cloneDir, 'log', "gnomish/${taskId}", '--name-only', '--format=').stdout()
        !allBlobPaths.contains('half-done.txt')
    }
}
