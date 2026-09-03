package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.git.TaskWorktreePath
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskRecord
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.git.WorktreeSalvager
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.UnaryOperator
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5, FR8, FR10, UX2 of add-git-workflow: {@code gnomish run --git --resume}. The branch's own
 * recorded outcome decides what resuming MEANS, and the five answers are deliberately different:
 * no outcome continues from the recorded position, {@code completed} only reports, {@code
 * escalated} re-opens the escalation dialog, {@code paused} asks for a checkpoint confirmation,
 * and {@code aborted} refuses outright rather than building on state a broken durability
 * guarantee left behind.
 *
 * <p>Driven through ports only (design D13(c) of split-into-modules), over a real
 * {@code RunnerOutcomeLoop}/{@code Engine} on the domain's scripted engine-port fakes and a
 * scripted console for the dialogs.
 *
 * <p>Added by task 8.7 of split-into-modules.
 */
class GitResumeRoutingSpec extends Specification implements RunChainFakes {

    @TempDir
    Path tempDir

    Path cloneDir
    Path worktreesRoot
    Path worktree

    TaskLifecycleStore lifecycleStore = Mock(TaskLifecycleStore)
    TaskWorktreeGit worktrees = Mock(TaskWorktreeGit)
    TaskBranchGit branches = Stub(TaskBranchGit)
    TaskStoreGit store = Stub(TaskStoreGit)
    WorktreeSalvager salvager = Mock(WorktreeSalvager)

    ScriptedExecutor executor = new ScriptedExecutor([completedRound()])
    TaskRecord record = freshRecord()

    /** Assignable, since setup() stubs the port once: the abort scenario swaps in a breaking one. */
    InMemoryAttemptPersistence persistence = new InMemoryAttemptPersistence()

    def setup() {
        cloneDir = tempDir.resolve('my-project')
        worktreesRoot = tempDir.resolve('worktrees')
        worktree = TaskWorktreePath.resolve(worktreesRoot, cloneDir, 'PROJ-1')
        Files.createDirectories(worktree)
        branches.locate(_, _) >> new BranchLocation.Local('refs/heads/gnomish/PROJ-1')
        branches.classifyShape(_, _) >> new BranchShape.InProgress()
        worktrees.ensureWorktree(_, _, _, _) >> worktree
        worktrees.salvage(_) >> salvager
        store.taskRepository(_, _) >> lifecycleStore
        store.attemptPersistence(_, _) >> { persistence }
        store.readRecordedState(_) >> TaskState.atStageStart('build')
        store.readTaskRecord(_) >> { record }
    }

    ScriptedConsoleIO console = new ScriptedConsoleIO([''])

    // FR1, FR3 of wire-host-mid-round-push (design D3): the git-mode host resume attaches the
    // TaskGit bundle's mid-round push decoration before assembling the continuation run.
    def "attaches the task-git mid-round push decoration on resume"() {
        given:
        def attached = []
        UnaryOperator<RoundEnvironmentSource> marker = { rounds ->
            rounds
        } as UnaryOperator<RoundEnvironmentSource>
        def runner = new GitResumeRunner(
                assemblyRunningLoop(executor, new ScriptedConsoleIO(['']),
                new Verdict.Pass(), attached),
                new TaskGit(store, branches, worktrees, marker), worktreesRoot, 'taskId')

        when:
        runner.run(cloneDir, 'PROJ-1', completingPipeline(), RunArguments.InteractiveMode.NONE, false)

        then:
        attached.size() == 1
        attached[0].is(marker)
    }

    private String resume(List<String> consoleScript = [''], boolean discardWork = false) {
        console = new ScriptedConsoleIO(consoleScript)
        def runner = new GitResumeRunner(assemblyRunningLoop(executor, console),
                new TaskGit(store, branches, worktrees), worktreesRoot, 'taskId')
        def originalOut = System.out
        def captured = new ByteArrayOutputStream()
        System.out = new PrintStream(captured, true, 'UTF-8')
        try {
            runner.run(cloneDir, 'PROJ-1', completingPipeline(), RunArguments.InteractiveMode.NONE, discardWork)
        } finally {
            System.out = originalOut
        }
        captured.toString('UTF-8')
    }

    // FR8, FR10: no recorded outcome means the branch was interrupted mid-round. The leftovers are
    // SALVAGED by default — committed as-is so the next round's gnome sees the half-done work and
    // the QC loop judges it — and the engine continues from the recorded position.
    def "salvages the interrupted round's leftovers and continues from the recorded position"() {
        when:
        resume()

        then:
        1 * salvager.salvage('PROJ-1')
        0 * salvager.discard()

        and: 'the engine really continued, and the run reached its terminal boundary'
        executor.requests.size() == 1
        1 * lifecycleStore.recordOutcome('PROJ-1', _ as TaskOutcome.Completed)
    }

    // FR8: --discard-work resets to HEAD instead, so the loop replays the round clean. The two are
    // mutually exclusive: doing both would commit the leftovers and then throw them away.
    def "discards the leftovers instead under --discard-work"() {
        when:
        resume([''], true)

        then:
        1 * salvager.discard()
        0 * salvager.salvage(_)
        executor.requests.size() == 1
    }

    // FR8, UX2: outcome `completed` prints the final status summary and stops — no engine round, no
    // further worktree or branch write. Re-running a delivered task would be paid work for nothing.
    def "reports a completed branch without running the engine or writing anything"() {
        given:
        record = recordWith(new RecordedOutcome.Completed())

        when:
        def output = resume()

        then:
        output.contains('PROJ-1')
        executor.requests.isEmpty()
        0 * lifecycleStore.recordOutcome(_, _)
        0 * salvager._
    }

    // FR5, FR8, UX2: outcome `escalated` re-opens the escalation dialog. A non-blank answer is
    // appended as a Decision (which also clears the recorded outcome in the same commit) and the
    // engine resumes from the dialog's reset state.
    def "re-opens the escalation dialog and records a non-blank answer as a decision"() {
        given:
        def report = new EscalationReport.DecisionNeeded('which database?', ['postgres', 'sqlite'])
        record = recordWith(new RecordedOutcome.Escalated(report), report)

        when:
        resume(['use postgres'])

        then:
        1 * lifecycleStore.appendDecision('PROJ-1', {
            it.body() == 'use postgres'
        }, _)

        and: 'and the run continued to its terminal boundary'
        executor.requests.size() == 1
        1 * lifecycleStore.recordOutcome('PROJ-1', _ as TaskOutcome.Completed)
    }

    // FR5: a BLANK answer resumes on the return alone — nothing is appended, mirroring the
    // in-process dialog's own blank-answer case, so the decision history stays truthful.
    def "appends no decision when the escalation answer is blank"() {
        given:
        def report = new EscalationReport.AttemptsExhausted(3)
        record = recordWith(new RecordedOutcome.Escalated(report), report)

        when:
        resume([''])

        then:
        0 * lifecycleStore.appendDecision(_, _, _)
        executor.requests.size() == 1
    }

    // FR5: `escalated` with no recorded report is a state the writer never produces (it always
    // writes both together), so it can only mean a corrupted branch — refused, not guessed at.
    def "refuses an escalated branch whose escalation report is missing"() {
        given:
        record = recordWith(new RecordedOutcome.Escalated(new EscalationReport.AttemptsExhausted(3)), null)

        when:
        resume()

        then:
        def ex = thrown(InternalErrorException)
        ex.message.contains('no lastEscalation recorded')
    }

    // FR8, UX2: outcome `paused` is a manual checkpoint, not a question — it states which stage
    // passed and waits for Enter, appending no decision, then resumes from the recorded state.
    def "confirms a manual checkpoint and resumes without appending a decision"() {
        given:
        record = recordWith(new RecordedOutcome.Paused('build'))

        when:
        resume([''])

        then: 'the checkpoint is STATED before the prompt — an operator has to know what passed'
        console.printed.any {
            it.contains("Stage 'build' passed") && it.contains('Manual checkpoint')
        }

        and:
        0 * lifecycleStore.appendDecision(_, _, _)
        executor.requests.size() == 1
    }

    // FR8: outcome `aborted` means a prior visit's durability guarantee broke. There is nothing to
    // resume automatically, and the refusal points at the kept worktree so the operator can look.
    def "refuses to resume an aborted branch, pointing at the kept worktree"() {
        given:
        record = recordWith(new RecordedOutcome.Aborted('build', 'persistence failed'))

        when:
        resume()

        then:
        def ex = thrown(UsageException)
        ex.message.contains('its last recorded outcome is Aborted')
        ex.message.contains(worktree.toString())

        and: 'nothing was run or written'
        executor.requests.isEmpty()
        0 * lifecycleStore.recordOutcome(_, _)
    }

    // FR8: a resumed run that ABORTS is still a terminal boundary — the outcome is recorded and the
    // worktree disposed of before the abort is re-thrown, so a broken durability guarantee does not
    // also lose the record of what happened.
    def "records and disposes on an aborted resumed run before rethrowing"() {
        given: 'persistence that breaks on its first write, which is what aborts the engine'
        persistence = new InMemoryAttemptPersistence(failOnCall: 1)

        when:
        resume()

        then:
        1 * lifecycleStore.recordOutcome('PROJ-1', _ as TaskOutcome.Aborted)
        1 * worktrees.cleanUp(cloneDir, worktree, _ as TaskOutcome.Aborted)

        and:
        thrown(AbortedException)
    }
}
