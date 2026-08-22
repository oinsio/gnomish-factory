package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.DeliveredBranchState
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.git.WorktreeSalvager
import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR9, FR10, FR12 (design D3, D10, D12) of add-tracker-port and add-claim-heartbeat: what a
 * just-claimed EXISTING branch is routed to. Four outcomes are possible and they are decided from
 * the branch's own recorded facts, never from the tracker (which post-claim always shows the task
 * as Working held by us):
 *
 * <ul>
 *   <li>the branch already delivered but the finish never landed — reconcile the finish, no engine
 *   <li>the branch parked but the park write never landed — reconcile the park, no engine
 *   <li>the branch is an ESCALATION-kind park — route through the decision dialog
 *   <li>anything else — resume the engine on the return alone
 * </ul>
 *
 * <p>Driven through ports only (design D13(c) of split-into-modules), over the real
 * {@code TakeDispositionResume} / {@code TakeResumeRunner} / {@code TakeDecisionResume} chain.
 *
 * <p>Added by task 8.7 of split-into-modules.
 */
class TakeResumeRoutingSpec extends Specification implements RunChainFakes {

    @TempDir
    Path tempDir

    Path worktreesRoot
    Path worktree

    /** The state.json a scenario wants read back; assignable, since setup() stubs the port once. */
    TaskState recordedState = TaskState.atStageStart('build')

    Tracker tracker = Mock(Tracker)
    TaskLifecycleStore lifecycleStore = Mock(TaskLifecycleStore)
    TaskBranchGit branches = Stub(TaskBranchGit)
    TaskWorktreeGit worktrees = Stub(TaskWorktreeGit)
    TaskStoreGit store = Stub(TaskStoreGit)

    def setup() {
        worktreesRoot = tempDir.resolve('worktrees')
        worktree = worktreesRoot.resolve('PROJ-1')
        Files.createDirectories(worktree)
        branches.locate(_, _) >> new BranchLocation.Local('refs/heads/gnomish/PROJ-1')
        worktrees.ensureWorktree(_, _, _, _) >> worktree
        worktrees.salvage(_) >> Stub(WorktreeSalvager)
        store.taskRepository(_, _) >> lifecycleStore
        store.attemptPersistence(_, _) >> new InMemoryAttemptPersistence()
        store.readRecordedState(_) >> { recordedState }
    }

    private TaskGit git() {
        new TaskGit(store, branches, worktrees)
    }

    /** The real routing chain, over the ports above. */
    private TakeDispositionResume resumeChain(ScriptedExecutor executor = new ScriptedExecutor([completedRound()])) {
        def runner = new TakeResumeRunner(assemblyRunning(executor), git(), worktreesRoot, 'taskId',
                new AbortHandler(tracker, FIXED_CLOCK), 3, [], new ClaimLossFlag())
        chainOver(runner, git())
    }

    /** The shared routing table (TakeDispositionResume) over HOST mechanics — design D8. */
    private TakeDispositionResume chainOver(TakeResumeRunner runner, TaskGit git) {
        def mechanics = new HostResumeMechanics(runner, git, worktreesRoot, completingPipeline())
        new TakeDispositionResume(mechanics, new TakeDecisionResume(mechanics), git)
    }

    private TakeResult resume(TakeDispositionResume chain, boolean discardWork = false) {
        chain.resumeExisting(CLONE_DIR, RunArguments.InteractiveMode.NONE,
                discardWork, 'PROJ-1', tracker, REF, INSTANCE)
    }

    // FR10, D10, NFR-C1: the branch's `.gnomish-task/` is GONE — the delivery cleanup commit ran but
    // the tracker finish never did (a dead instance at the finish line). The delivered state is
    // recovered from branch history and the finish posted, with ZERO engine rounds: the paid work
    // already happened and must not be repeated.
    def "reconciles a delivered-but-unfinished branch without running the engine"() {
        given:
        def executor = new ScriptedExecutor([completedRound()])
        store.readTaskRecord(_) >> {
            throw new UncheckedIOException(new NoSuchFileException('task.json'))
        }
        tracker.fetchTask(_) >> heldByUs()
        branches.readDelivered(_, _) >> new DeliveredBranchState(
                new TaskContext('PROJ-1', 'title', 'body', List.<Decision> of()), TaskState.atStageStart('build'))

        when:
        def result = resume(resumeChain(executor))

        then:
        1 * tracker.finish(REF, _)
        result instanceof TakeResult.Delivered

        and: 'no round was paid for'
        executor.requests.isEmpty()
    }

    // FR10: only the "cleanup already ran" shape is reconciled. Any other I/O fault reading the
    // branch is a genuine failure and must surface, not be mistaken for a delivered branch.
    def "lets an unrelated I/O fault reading the branch propagate"() {
        given:
        store.readTaskRecord(_) >> {
            throw new UncheckedIOException(new IOException('disk on fire'))
        }

        when:
        resume(resumeChain())

        then:
        def ex = thrown(UncheckedIOException)
        ex.cause.message == 'disk on fire'
    }

    // FR10, D10, NFR-C1: a park whose durable "tracker-write pending" marker is STILL SET means the
    // park write never landed. The park is re-sent from the branch's own recorded outcome, again
    // with zero engine rounds — and the marker is cleared only once that write confirms.
    def "reconciles an orphaned park without running the engine"() {
        given:
        def executor = new ScriptedExecutor([completedRound()])
        store.readTaskRecord(_) >> recordWith(new RecordedOutcome.Paused('build'), null, true)
        tracker.fetchTask(_) >> heldByUs()

        when:
        def result = resume(resumeChain(executor))

        then:
        1 * tracker.park(REF, ParkReason.CHECKPOINT, _)
        1 * lifecycleStore.confirmTerminalWrite('PROJ-1')
        result instanceof TakeResult.AwaitingHuman
        executor.requests.isEmpty()
    }

    // FR10: a CLEARED marker means the park did land — a human answered and returned the task — so
    // this is an ordinary resume, not a reconcile. Same recorded outcome, opposite route.
    def "resumes normally when the park's marker was already cleared"() {
        given:
        store.readTaskRecord(_) >> recordWith(new RecordedOutcome.Paused('build'), null, false)
        tracker.fetchTask(_) >> heldByUs()

        when:
        def result = resume(resumeChain())

        then:
        0 * tracker.park(_, _, _)
        1 * tracker.finish(REF, _)
        result instanceof TakeResult.Delivered
    }

    // FR9, FR12, D3, D12: an ESCALATION-kind park with a DecisionNeeded report and NO human reply
    // yet is re-parked with the question restated — the run must not proceed on a question nobody
    // answered.
    def "re-parks a DecisionNeeded escalation that has no reply yet, restating the question"() {
        given:
        def executor = new ScriptedExecutor([completedRound()])
        def report = new EscalationReport.DecisionNeeded('which database?', ['postgres', 'sqlite'])
        store.readTaskRecord(_) >> recordWith(new RecordedOutcome.Escalated(report), report, false)
        tracker.fetchTask(_) >> heldByUs()

        when:
        def result = resume(resumeChain(executor))

        then:
        1 * tracker.collectDecisions(REF) >> []
        1 * tracker.park(REF, ParkReason.ESCALATION, {
            it.contains('which database?')
        })
        result instanceof TakeResult.AwaitingHuman
        executor.requests.isEmpty()
    }

    // FR9, FR12, D3: with a reply present the decision is acknowledged on the tracker and appended
    // to the task's own decision history (author "tracker" — it came from a comment, not a
    // console), then the engine resumes carrying it.
    def "acknowledges the latest reply, appends it as a decision, and resumes"() {
        given:
        def report = new EscalationReport.DecisionNeeded('which database?', ['postgres', 'sqlite'])
        store.readTaskRecord(_) >> recordWith(new RecordedOutcome.Escalated(report), report, false)
        tracker.fetchTask(_) >> heldByUs()

        when:
        def result = resume(resumeChain())

        then: 'the LAST reply is the one acted on'
        1 * tracker.collectDecisions(REF) >> [
            new HumanReply('an earlier thought', NOW.minusSeconds(60)),
            new HumanReply('use postgres', NOW),
        ]
        1 * tracker.acknowledgeDecision(REF, 'use postgres')
        1 * lifecycleStore.appendDecision('PROJ-1', {
            it.body() == 'use postgres' && it.author() == 'tracker'
        })

        and:
        result instanceof TakeResult.Delivered
    }

    // FR9, D12: an AttemptsExhausted park may resume "on the return alone" — a human returning the
    // task IS the authorization, so with no reply the run proceeds and appends NO decision.
    def "resumes an AttemptsExhausted escalation on the return alone, appending no decision"() {
        given:
        def report = new EscalationReport.AttemptsExhausted(3)
        store.readTaskRecord(_) >> recordWith(new RecordedOutcome.Escalated(report), report, false)
        tracker.fetchTask(_) >> heldByUs()

        when:
        def result = resume(resumeChain())

        then:
        1 * tracker.collectDecisions(REF) >> []
        0 * tracker.park(_, _, _)
        0 * lifecycleStore.appendDecision(_, _)
        result instanceof TakeResult.Delivered
    }

    // FR9: an ordinary resume salvages the interrupted attempt's leftovers by default, but
    // --discard-work throws them away instead. The two are mutually exclusive, and which one runs
    // is the whole meaning of the flag.
    def "salvages leftovers by default and discards them under --discard-work"() {
        given:
        def salvager = Mock(WorktreeSalvager)
        def ownWorktrees = Stub(TaskWorktreeGit) {
            ensureWorktree(_, _, _, _) >> worktree
            salvage(worktree) >> salvager
        }
        store.readTaskRecord(_) >> recordWith(null, null, false)
        tracker.fetchTask(_) >> heldByUs()
        def runner = new TakeResumeRunner(assemblyRunning(new ScriptedExecutor([completedRound()])),
        new TaskGit(store, branches, ownWorktrees), worktreesRoot, 'taskId',
        new AbortHandler(tracker, FIXED_CLOCK), 3, [], new ClaimLossFlag())
        def chain = chainOver(runner, new TaskGit(store, branches, ownWorktrees))

        when:
        resume(chain, discardWork)

        then:
        (discardWork ? 0 : 1) * salvager.salvage('PROJ-1')
        (discardWork ? 1 : 0) * salvager.discard()

        where:
        discardWork << [false, true]
    }


    // FR10, D10: the other park kind. An orphaned ESCALATED park is re-sent through the escalation
    // exit, not the pause exit — the two write different tracker states, so the recorded outcome
    // decides which, and both clear the pending marker only once the write confirms.
    def "reconciles an orphaned ESCALATED park through the escalation exit"() {
        given:
        def executor = new ScriptedExecutor([completedRound()])
        def report = new EscalationReport.AttemptsExhausted(3)
        store.readTaskRecord(_) >> recordWith(new RecordedOutcome.Escalated(report), report, true)
        tracker.fetchTask(_) >> heldByUs()

        when:
        def result = resume(resumeChain(executor))

        then:
        1 * tracker.park(REF, ParkReason.ESCALATION, _)
        1 * lifecycleStore.confirmTerminalWrite('PROJ-1')
        result instanceof TakeResult.AwaitingHuman
        executor.requests.isEmpty()
    }

    // FR12: an appended decision is stamped with the stage the run was parked AT, so the history
    // reads back in pipeline order. A park recorded at the pipeline's END belongs to no stage, and
    // the decision carries no stage rather than a fabricated one.
    def "stamps an appended decision with the parked stage, or with none at the pipeline end"() {
        given:
        def report = new EscalationReport.DecisionNeeded('which database?', ['postgres'])
        store.readTaskRecord(_) >> recordWith(new RecordedOutcome.Escalated(report), report, false)
        recordedState = finalState
        tracker.fetchTask(_) >> heldByUs()

        when:
        resume(resumeChain())

        then:
        1 * tracker.collectDecisions(REF) >> [
            new HumanReply('use postgres', NOW)
        ]
        1 * lifecycleStore.appendDecision('PROJ-1', {
            it.stage() == expectedStage
        })

        where:
        finalState || expectedStage
        TaskState.atStageStart('build') || 'build'
        new TaskState(new Position.PipelineEnd(), 0, [], ExecutorUsage.none()) || null
    }

    // FR9, FR13: a run whose claim is lost mid-flight does not finish the task — the engine stops at
    // the nearest round boundary and the revocation handler takes over, so no tracker write is made
    // on behalf of a claim this instance no longer holds.
    def "hands a run whose claim was revoked mid-flight to the revocation handler"() {
        given:
        def lostFlag = new ClaimLossFlag()
        lostFlag.claimLost(REF, 'taken over')
        store.readTaskRecord(_) >> recordWith(null, null, false)
        tracker.fetchTask(_) >> heldByUs()
        def runner = new TakeResumeRunner(assemblyRunning(new ScriptedExecutor([completedRound()])),
        git(), worktreesRoot, 'taskId', new AbortHandler(tracker, FIXED_CLOCK), 3, [], lostFlag)

        when:
        def result = resume(chainOver(runner, git()))

        then: 'the task is never finished on a claim we lost'
        0 * tracker.finish(_, _)
        result instanceof TakeResult.Revoked
    }

    // FR10, D10: a park the ENGINE itself produces clears the pending marker the same way a
    // reconciled one does — the marker exists to say "the tracker write has not confirmed yet", so
    // it is cleared on confirmation whichever path wrote it.
    def "clears the pending marker when the engine's own run parks"() {
        given:
        store.readTaskRecord(_) >> recordWith(null, null, false)
        tracker.fetchTask(_) >> heldByUs()
        def runner = new TakeResumeRunner(
                assemblyRunning(new ScriptedExecutor([
                    completedRound(),
                    completedRound()
                ]), new Verdict.Fail([])),
                git(), worktreesRoot, 'taskId', new AbortHandler(tracker, FIXED_CLOCK), 3, [], new ClaimLossFlag())

        when:
        def result = resume(chainOver(runner, git()))

        then:
        1 * tracker.park(REF, ParkReason.ESCALATION, _)
        1 * lifecycleStore.confirmTerminalWrite('PROJ-1')
        result instanceof TakeResult.AwaitingHuman
    }
}
