package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.git.TaskWorktreePath
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.app.port.git.*
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.UnaryOperator
import spock.lang.Specification
import spock.lang.TempDir
/**
 * FR5, FR8, FR10, UX2 of add-git-workflow: {@code gnomish run --git --resume}. The branch's own
 * recorded outcome decides what resuming MEANS, and the five answers are deliberately different:
 * no outcome continues from the recorded position, {@code completed} only reports, {@code
 * escalated} resolves the operator's {@code --decision} through {@code EscalationResume} (FR3,
 * FR4, FR9, NFR-R1 of make-run-headless), {@code paused} continues without a prompt (FR5), and
 * {@code aborted} refuses outright rather than building on state a broken durability guarantee
 * left behind.
 *
 * <p>Driven through ports only (design D13(c) of split-into-modules), over a real
 * {@code RunnerOutcomeLoop}/{@code Engine} on the domain's scripted engine-port fakes and a
 * console whose reader fails the spec: no resume path reads standard input (FR6).
 *
 * <p>Added by task 8.7 of split-into-modules.
 */
class GitResumeRoutingSpec extends Specification implements RunChainFakes {

    @TempDir
    Path tempDir

    Path cloneDir
    RegisteredClone registeredClone
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

    /**
     * What each state.json read at the tip answers, in call order — a closure rather than a value,
     * so the impossible-state scenarios can empty one read without re-stubbing a port setup()
     * already stubbed (FR1 of fix-envelope-medium).
     */
    Closure<Optional<TaskState>> stateRead = {
        Optional.of(TaskState.atStageStart('build'))
    }

    def setup() {
        cloneDir = tempDir.resolve('my-project')
        registeredClone = RegisteredCloneFixture.unregistered(tempDir.resolve('home'), cloneDir)
        worktree = TaskWorktreePath.resolve(registeredClone, 'PROJ-1')
        Files.createDirectories(worktree)
        branches.locate(_, _) >> new BranchLocation.Local('refs/heads/gnomish/PROJ-1')
        branches.classifyShape(_, _) >> new BranchShape.InProgress()
        worktrees.ensureWorktree(_, _, _) >> worktree
        worktrees.salvage(_) >> salvager
        store.taskRepository(_) >> lifecycleStore
        store.attemptPersistence(_, _) >> { persistence }
        store.readRecordedState(_) >> { stateRead.call() }
        store.readTaskRecord(_) >> { Optional.ofNullable(record) }
    }

    ScriptedConsoleIO console = unreadableConsole()

    /** FR6 of make-run-headless: a console a resume may print to but never read from. */
    private static ScriptedConsoleIO unreadableConsole() {
        new ScriptedConsoleIO() {
                    @Override
                    String readLine() {
                        throw new AssertionError('a headless resume read the console')
                    }
                }
    }

    // FR1, FR3 of wire-host-mid-round-push (design D3): the git-mode host resume attaches the
    // TaskGit bundle's mid-round push decoration before assembling the continuation run.
    def "attaches the task-git mid-round push decoration on resume"() {
        given:
        def attached = []
        UnaryOperator<RoundEnvironmentSource> marker = { rounds ->
            rounds
        } as UnaryOperator<RoundEnvironmentSource>
        def runner = new GitResumeRunner(
                assemblyRunningLoop(executor, unreadableConsole(),
                new Verdict.Pass(), attached),
                new TaskGit(store, branches, worktrees, marker, new ClaimEpochBook()), registeredClone, 'taskId')

        when:
        runner.run(new RunOrder(cloneDir, null, completingPipeline(), false),
                'PROJ-1', null)

        then:
        attached.size() == 1
        attached[0].is(marker)
    }

    /** Every law binding the resumed chain assembled with, in order (FR12 of add-base-ref-resolution). */
    List lawBindings = []

    /** Resumes PROJ-1 with the given {@code --decision} ({@code null} for none) over a console that is never read. */
    private String resume(String decision = null, boolean discardWork = false, Verdict verdict = new Verdict.Pass()) {
        console = unreadableConsole()
        def runner = new GitResumeRunner(assemblyRunningLoop(executor, console, verdict, [], lawBindings),
        new TaskGit(store, branches, worktrees, new ClaimEpochBook()), registeredClone, 'taskId')
        def originalOut = System.out
        def captured = new ByteArrayOutputStream()
        System.out = new PrintStream(captured, true, 'UTF-8')
        try {
            runner.run(
                    new RunOrder(cloneDir, null, completingPipeline(), discardWork),
                    'PROJ-1', decision)
        } finally {
            System.out = originalOut
        }
        captured.toString('UTF-8')
    }

    // FR7, FR12, D13 of add-base-ref-resolution: a manual resume binds the law from the LOCAL tip
    // of the ref its branch is pinned to — the clone's own checkout plays no part, so a task
    // pinned to release/1.18 keeps reading release/1.18's law in a clone sitting on main. The pin
    // records the namespace (D7, revised 2026-09-10), so the revision is fully qualified and a
    // local tag planted over the branch name cannot win git's bare-name lookup here either.
    def "binds the resumed law from the task's pinned base ref, not from the clone's checkout"() {
        given:
        record = recordPinnedTo('release/1.18')

        when:
        resume()

        then:
        lawBindings == [
            new LawBinding.AtRevision(cloneDir, 'refs/heads/release/1.18')
        ]
    }

    // FR7 of add-base-ref-resolution: a legacy branch carries no baseRef, so the recorded base
    // commit — itself a revision the clone resolves — names the law, the same fallback the
    // tracker-driven resume takes.
    def "falls back to the recorded base commit when the branch carries no pin"() {
        given:
        record = freshRecord()

        when:
        resume()

        then:
        lawBindings == [
            new LawBinding.AtRevision(cloneDir, 'base-sha')
        ]
    }

    // FR8, UX3 of add-base-ref-resolution: a manual run started without --base pinned the literal
    // HEAD ref, so resuming it still binds the clone's checkout — the offline manual contract.
    def "keeps binding the clone's checkout for a task started by a manual run without --base"() {
        given: 'a manual pin: a ref and a rule, and no kind — the manual tier classifies nothing'
        record = recordManuallyPinnedTo('HEAD')

        when:
        resume()

        then:
        lawBindings == [
            LawBinding.atRevision(cloneDir, GitObjects.HEAD)
        ]
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
        1 * lifecycleStore.recordOutcome('PROJ-1', _ as TaskOutcome.Completed, TrackerWrite.OWED)
    }

    // FR8: --discard-work resets to HEAD instead, so the loop replays the round clean. The two are
    // mutually exclusive: doing both would commit the leftovers and then throw them away.
    def "discards the leftovers instead under --discard-work"() {
        when:
        resume(null, true)

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
        resume()

        then: 'the summary reached the operator through the run\'s own console owner'
        console.printed.join('').contains('PROJ-1')
        executor.requests.isEmpty()
        0 * lifecycleStore.recordOutcome(_, _, _)
        0 * salvager._
    }

    // FR5, FR8, UX2; FR3, NFR-R1 of make-run-headless: outcome `escalated` with --decision appends
    // the decision through the one call that also resets outcome and attempts in the same commit,
    // and the engine resumes from the reset state. Nothing is printed before the engine runs.
    def "records a --decision over an escalated task as the operator's, scoped to the stage, in one write"() {
        given:
        def report = new EscalationReport.DecisionNeeded(UntrustedText.agent('which database?'), [
            UntrustedText.agent('postgres'),
            UntrustedText.agent('sqlite')
        ])
        record = recordWith(new RecordedOutcome.Escalated(report), report)

        when:
        resume('use postgres')

        then: 'the answer is recorded as the operator\'s, scoped to the stage it answers, with the reset state'
        1 * lifecycleStore.appendDecision('PROJ-1', {
            it.body() == 'use postgres' && it.author() == 'operator' && it.stage() == 'build'
        }, {
            it.attemptsUsed() == 0
        })

        and: 'the question was not restated — the flag answered it'
        !console.printed.any {
            it.contains('which database?')
        }

        and: 'and the run continued to its terminal boundary'
        executor.requests.size() == 1
        1 * lifecycleStore.recordOutcome('PROJ-1', _ as TaskOutcome.Completed, TrackerWrite.OWED)
    }

    // FR4 of make-run-headless: no --decision over an AttemptsExhausted park resumes on the reset
    // alone — nothing is appended, so the decision history stays truthful.
    def "appends no decision when an escalated task is resumed without --decision"() {
        given:
        def report = new EscalationReport.AttemptsExhausted(3)
        record = recordWith(new RecordedOutcome.Escalated(report), report)

        when:
        resume()

        then:
        0 * lifecycleStore.appendDecision(_, _, _)
        executor.requests.size() == 1
    }

    // FR4 of make-run-headless: a DecisionNeeded resumed without --decision is refused — the
    // question and the return path are restated, no round runs, nothing is written.
    def "restates a DecisionNeeded resumed without --decision and writes nothing"() {
        given:
        def report = new EscalationReport.DecisionNeeded(UntrustedText.agent('which database?'), [
            UntrustedText.agent('postgres')
        ])
        record = recordWith(new RecordedOutcome.Escalated(report), report)

        when:
        resume()

        then:
        def refused = thrown(DecisionRequiredException)
        refused.stopRecord().contains("--dir=${cloneDir} --resume=PROJ-1")
        console.printed.any {
            it.contains('which database?') && it.contains('--resume=PROJ-1 [--decision="..."]')
        }

        and:
        executor.requests.isEmpty()
        0 * lifecycleStore._
        0 * salvager._
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

    // FR8, UX2; FR5 of make-run-headless: outcome `paused` is a manual checkpoint, not a question —
    // the resume is the confirmation, so nothing is printed, nothing asked, nothing appended, and
    // the engine continues from the recorded (already advanced) state.
    def "continues a paused task without a checkpoint line, a prompt or a decision"() {
        given:
        record = recordWith(new RecordedOutcome.Paused('build'))

        when:
        resume()

        then:
        !console.printed.any {
            it.contains('Manual checkpoint')
        }
        0 * lifecycleStore.appendDecision(_, _, _)
        executor.requests.size() == 1
    }

    // FR1, FR10 of make-run-headless (design D8): a resumed run that stops again records the park
    // through the same recorder, keeps the worktree, and leaves by its outcome with the return path.
    def "records the park of a resumed run that escalates again and exits by it"() {
        when:
        resume(null, false, new Verdict.Fail([]))

        then:
        def stop = thrown(RunParkedException)
        !stop.checkpoint()
        stop.stopRecord().contains("--dir=${cloneDir} --resume=PROJ-1")

        and:
        1 * lifecycleStore.recordOutcome('PROJ-1', _ as TaskOutcome.Escalated, TrackerWrite.NONE)
        0 * lifecycleStore.confirmTerminalWrite(_)
        0 * lifecycleStore.finishCleanup(_)
        1 * worktrees.cleanUp(cloneDir, worktree, _ as TaskOutcome.Escalated)
    }

    // FR9 of make-run-headless: a --decision answers an escalation; over any other recorded outcome
    // it is a usage error naming the conflict, raised after task.json is read and before any branch
    // write or salvage.
    def "refuses a --decision over a task whose recorded outcome is #label, writing nothing"() {
        given:
        record = recorded == null ? freshRecord() : recordWith(recorded)

        when:
        resume('nobody asked')

        then:
        def ex = thrown(UsageException)
        ex.message.contains('--decision')
        ex.message.contains('PROJ-1')
        ex.message.contains(named)

        and:
        executor.requests.isEmpty()
        0 * lifecycleStore._
        0 * salvager._

        where:
        label | recorded | named
        'paused' | new RecordedOutcome.Paused('build') | "paused at a manual checkpoint after stage 'build'"
        'completed' | new RecordedOutcome.Completed() | 'completed'
        'aborted' | new RecordedOutcome.Aborted('build', UntrustedText.branchDocument('persistence failed')) | 'aborted'
        'not recorded' | null | 'interrupted run with no recorded outcome'
    }

    // FR8: outcome `aborted` means a prior visit's durability guarantee broke. There is nothing to
    // resume automatically, and the refusal points at the kept worktree so the operator can look.
    def "refuses to resume an aborted branch, pointing at the kept worktree"() {
        given:
        record = recordWith(new RecordedOutcome.Aborted('build', UntrustedText.branchDocument('persistence failed')))

        when:
        resume()

        then:
        def ex = thrown(UsageException)
        ex.message.contains('its last recorded outcome is Aborted')
        ex.message.contains(worktree.toString())

        and: 'nothing was run or written'
        executor.requests.isEmpty()
        0 * lifecycleStore.recordOutcome(_, _, _)
    }

    // FR1, design D2 of fix-envelope-medium: on the manual-run paths the envelope was committed by
    //     this very run, so an empty read at HEAD is an invariant violation, not a route. There is
    //     no delivered arm here — that one belongs to the tracker-driven mechanics — so each site
    //     reports the application's own impossible-state exception, naming the task and the
    //     worktree whose HEAD should have carried the file.
    def "FR1: #site reports an impossible state when the tip carries no envelope"() {
        given:
        absent.call(this)

        when:
        resume()

        then:
        def ex = thrown(InternalErrorException)
        ex.message.contains('PROJ-1')
        ex.message.contains('absent at HEAD')
        ex.message.contains(worktree.toString())

        where:
        site | absent
        'the resume bootstrap' | { GitResumeRoutingSpec it ->
            it.record = null
        }
        'the final-state readback' | { GitResumeRoutingSpec it ->
            it.stateRead = {
                Optional.<TaskState> empty()
            }
        }
        'the terminal-boundary read'| { GitResumeRoutingSpec it ->
            // The continuation reads the state again once the engine reaches PipelineEnd, so only
            // that second read is emptied — the first one must answer, or the run never gets there.
            int reads = 0
            it.stateRead = {
                reads++ == 0 ? Optional.of(TaskState.atStageStart('build')) : Optional.<TaskState> empty()
            }
        }
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
        1 * lifecycleStore.recordOutcome('PROJ-1', _ as TaskOutcome.Aborted, TrackerWrite.OWED)
        1 * worktrees.cleanUp(cloneDir, worktree, _ as TaskOutcome.Aborted)

        and:
        thrown(AbortedException)
    }
}
