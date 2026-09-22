package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.git.*
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.UnaryOperator
import spock.lang.Specification
import spock.lang.TempDir
/**
 * FR2, FR9, FR10 of harden-task-branch-contract: every recoverable shape reaches the loaded-branch
 * table, and the one shape that is already delivered — {@code CompletedUncleaned} — finishes through
 * the intent→effect→receipt tail instead of the engine.
 *
 * <p>Sibling of {@code TakeShapeRoutingSpec}, which owns the shapes the table REFUSES. This one owns
 * the shapes it accepts: that each of them is routed at all (the sealed switch names five, and a
 * spec that only ever passes {@code Created} would let four of them rot), and that the delivered tip
 * pays no engine round while still committing its cleanup (NFR-C1).
 */
class TakeResumeShapeTailSpec extends Specification implements RunChainFakes {

    @TempDir
    Path tempDir

    Path worktreesRoot
    Path worktree

    Tracker tracker = Mock(Tracker)
    TaskLifecycleStore lifecycleStore = Mock(TaskLifecycleStore)
    TaskBranchGit branches = Stub(TaskBranchGit)
    TaskWorktreeGit worktrees = Mock(TaskWorktreeGit)
    TaskStoreGit store = Stub(TaskStoreGit)
    ScriptedExecutor executor = new ScriptedExecutor([completedRound()])

    /** The {@code task.json} the resumed branch carries; reassigned per scenario before chain(). */
    def record = freshRecord()

    /** The round journal the store hands the run; reassigned by the scenario that aborts. */
    AttemptPersistence journal = new InMemoryAttemptPersistence()

    /** What the worktree's state.json read answers; reassigned by the scenario that deletes it. */
    Closure<Optional<TaskState>> recordedState = {
        Optional.of(TaskState.atStageStart('build'))
    }

    BaseRefGit baseRefGit = resumingBaseRefGit()

    def setup() {
        worktreesRoot = tempDir.resolve('worktrees')
        worktree = worktreesRoot.resolve('PROJ-1')
        Files.createDirectories(worktree)
        branches.locate(_, _) >> new BranchLocation.Local('refs/heads/gnomish/PROJ-1')
        worktrees.ensureWorktree(_, _, _, _) >> worktree
        worktrees.salvage(_) >> Stub(WorktreeSalvager)
        store.taskRepository(_, _) >> lifecycleStore
        store.attemptPersistence(_, _) >> { journal }
        store.readRecordedState(_) >> { recordedState() }
        store.readTaskRecord(_) >> { Optional.ofNullable(record) }
        tracker.fetchTask(_) >> heldByUs()
    }

    /** The real host resume chain over the ports above. */
    private TakeDispositionResume chain() {
        def git = new TaskGit(store, branches, worktrees, UnaryOperator.identity(), baseRefGit, new ClaimEpochBook())
        def runner = new TakeResumeRunner(assemblyRunning(executor), git,
                worktreesRoot, 'taskId', new AbortHandler(tracker, FIXED_CLOCK), 3, [], new ClaimLossFlag())
        def mechanics = new HostResumeMechanics(runner, git, worktreesRoot, completingPipeline())
        new TakeDispositionResume(mechanics, new TakeDecisionResume(mechanics), git)
    }

    private TakeResult resume(BranchShape shape) {
        chain().resumeExisting(CLONE_DIR, shape, RunArguments.InteractiveMode.NONE, false,
                'PROJ-1', tracker, REF, INSTANCE)
    }

    // FR2: the sealed switch routes five shapes into the loaded-branch table, and each of them
    // really resumes — a table entry no spec ever selects is an entry nothing holds to its meaning.
    // Parked and CompletedUncleaned are additionally the non-clean arm, whose failures are named as
    // failed recoveries (FR14), so passing them here exercises that arm too.
    def "FR2: a #shape.class.simpleName branch resumes through the loaded-branch table"() {
        when:
        def result = resume(shape)

        then: 'the run reached its terminal boundary through the engine'
        result instanceof TakeResult.Delivered
        executor.requests.size() == 1
        1 * tracker.finish(REF, _)

        where:
        shape << [
            new BranchShape.Created(),
            new BranchShape.InProgress(),
            new BranchShape.Answered(),
            new BranchShape.Parked(),
        ]
    }

    // FR9, FR10, NFR-C1: the tip records Completed with its envelope still there — the kill window
    // between the outcome commit and the tracker finish. The deferred finish is written and the
    // cleanup commit and worktree disposal follow it, with no engine round paid for work already
    // delivered.
    def "FR9: a CompletedUncleaned tip finishes and cleans up without re-entering the engine"() {
        given:
        record = recordWith(new RecordedOutcome.Completed())

        when:
        def result = resume(new BranchShape.CompletedUncleaned())

        then: 'the deferred finish is written, then its destructive tail runs'
        1 * tracker.finish(REF, _)

        then:
        1 * lifecycleStore.finishCleanup('PROJ-1')
        1 * worktrees.cleanUp(CLONE_DIR, worktree, _ as TaskOutcome.Completed)

        and: 'no paid round was re-run'
        result instanceof TakeResult.Delivered
        executor.requests.isEmpty()
    }

    // FR1, FR5 of fix-envelope-medium: the tip carries task.json, so readTaskRecord answers a
    // PRESENT record and the loaded-branch table takes the uncleaned finish. The delivered
    // reconcile is what an EMPTY answer means, and the two routes are one branch apart in
    // TakeLoadedBranchRoutes — since the port stopped reporting absence as an I/O failure, the
    // typed value is the only thing that tells them apart, so the wrong route would silently
    // re-read branch history instead of the tip the caller just classified.
    def "FR1, FR5: a present Completed record routes to finishUncleaned, never to deliverCompleted"() {
        given: 'a tip recording Completed, with a recorded state past the first stage'
        record = recordWith(new RecordedOutcome.Completed())
        recordedState = {
            Optional.of(TaskState.atStageStart('deploy'))
        }
        and: 'the delivered route, if taken, says so loudly instead of answering a dummy state'
        branches.readDelivered(_, _) >> {
            throw new AssertionError(
            'deliverCompleted was taken: a present Completed record must route to finishUncleaned' as Object)
        }

        when:
        def result = resume(new BranchShape.CompletedUncleaned())

        then: 'the uncleaned finish ran — the deferred write and its destructive tail'
        1 * tracker.finish(REF, _)
        1 * lifecycleStore.finishCleanup('PROJ-1')

        and: 'the report carries the state the tip recorded, not a fabricated or re-read one'
        result instanceof TakeResult.Delivered
        ((TakeResult.Delivered) result).finalState() == TaskState.atStageStart('deploy')
    }

    // FR9, FR15: the cleanup commit deletes .gnomish-task/ from the worktree, so the final state
    // must be read BEFORE it — a read after the delete finds nothing and falls back to the
    // pre-contract fabrication, handing disposal a first-stage state for a delivered task.
    def "FR9: the disposal outcome carries the state recorded on the tip, read before cleanup deletes it"() {
        given: 'a tip whose recorded state is past the first stage, and a store that loses it on cleanup'
        record = recordWith(new RecordedOutcome.Completed())
        def cleaned = false
        lifecycleStore.finishCleanup('PROJ-1') >> { cleaned = true }
        // FR5 of fix-envelope-medium: after the cleanup commit the tip carries no envelope, so the
        // read answers empty — the typed absence HostResumeMechanics turns into the first-stage
        // fabrication. Reading the state before the cleanup is what keeps that fabrication away
        // from a delivered task's disposal.
        recordedState = {
            cleaned ? Optional.<TaskState> empty() : Optional.of(TaskState.atStageStart('deploy'))
        }

        when:
        resume(new BranchShape.CompletedUncleaned())

        then: 'disposal received the recorded state, not a fabricated first-stage one'
        1 * worktrees.cleanUp(CLONE_DIR, worktree, { TaskOutcome.Completed outcome ->
            outcome.finalState() == TaskState.atStageStart('deploy')
        })
    }

    // FR10: an Aborted run has no external effect to sequence around — its tracker write is
    // best-effort and carries no marker — so the whole record-then-dispose pair runs as one step.
    // The worktree survives it (FR6 of add-git-workflow): an aborted run is evidence to look at.
    def "FR10: an aborted run records its outcome and disposes by outcome, in one step"() {
        given: 'a round journal that cannot be written, which the engine turns into Aborted'
        journal = { String taskId, TaskState state, ToolTrace trace ->
            throw new IllegalStateException('journal unwritable')
        } as AttemptPersistence

        when:
        def result = resume(new BranchShape.InProgress())

        then:
        1 * lifecycleStore.recordOutcome('PROJ-1', _ as TaskOutcome.Aborted)

        then:
        1 * worktrees.cleanUp(CLONE_DIR, worktree, _ as TaskOutcome.Aborted)

        and:
        result instanceof TakeResult.Aborted
    }
}
