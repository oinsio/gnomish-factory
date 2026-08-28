package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.git.WorktreeSalvager
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3, FR4, FR5, M2 of fix-lifecycle-push over the host resume chain: where a resumed run touches
 * the remote, and what the human is told when it could not. Resume start reconciles origin up to the
 * local branch tip; a fresh park runs the delivery fence BEFORE the tracker write announcing it; and
 * a deferred (reconciled) park runs that same fence before re-posting. An exhausted fence never
 * blocks either park — it only adds the origin-behind line to the report.
 *
 * <p>Split out of {@code TakeResumeRoutingSpec}, which owns the routing table itself.
 */
class TakeResumeReplicationSpec extends Specification implements RunChainFakes {

    private static final String BEHIND_NOTE =
    'Note: origin is behind this park — branch gnomish/PROJ-1 could not be pushed.'

    @TempDir
    Path tempDir

    Path worktreesRoot
    Path worktree

    Tracker tracker = Mock(Tracker)
    TaskLifecycleStore lifecycleStore = Mock(TaskLifecycleStore)
    TaskBranchGit branches = Mock(TaskBranchGit)
    TaskWorktreeGit worktrees = Stub(TaskWorktreeGit)
    TaskStoreGit store = Stub(TaskStoreGit)

    def setup() {
        worktreesRoot = tempDir.resolve('worktrees')
        worktree = worktreesRoot.resolve('PROJ-1')
        Files.createDirectories(worktree)
        branches.locate(_, _) >> new BranchLocation.Local('refs/heads/gnomish/PROJ-1')
        branches.classifyShape(_, _) >> new BranchShape.InProgress()
        worktrees.ensureWorktree(_, _, _, _) >> worktree
        worktrees.salvage(_) >> Stub(WorktreeSalvager)
        store.taskRepository(_, _) >> lifecycleStore
        store.attemptPersistence(_, _) >> new InMemoryAttemptPersistence()
        store.readRecordedState(_) >> TaskState.atStageStart('build')
        tracker.fetchTask(_) >> heldByUs()
    }

    private TaskGit git() {
        new TaskGit(store, branches, worktrees)
    }

    /** The real routing chain over the ports above; {@code verdict} decides whether a run parks. */
    private TakeDispositionResume chain(ScriptedExecutor executor, Verdict verdict = new Verdict.Pass()) {
        def git = git()
        def runner = new TakeResumeRunner(assemblyRunning(executor, verdict), git, worktreesRoot, 'taskId',
                new AbortHandler(tracker, FIXED_CLOCK), 3, [], new ClaimLossFlag())
        def mechanics = new HostResumeMechanics(runner, git, worktreesRoot, completingPipeline())
        new TakeDispositionResume(mechanics, new TakeDecisionResume(mechanics), git)
    }

    /** A chain whose engine run escalates: two rounds, the last verification failing. */
    private TakeDispositionResume parkingChain() {
        chain(new ScriptedExecutor([
            completedRound(),
            completedRound()
        ]), new Verdict.Fail([]))
    }

    private TakeResult resume(TakeDispositionResume chain) {
        chain.resumeExisting(
                CLONE_DIR, new BranchShape.InProgress(), RunArguments.InteractiveMode.NONE, false, 'PROJ-1', tracker, REF, INSTANCE)
    }

    // FR3: resume start is a touchpoint — after the worktree's own divergence reconcile pulls local
    // up to origin, this pushes origin up to local, delivering a commit an earlier instance recorded
    // but never got pushed.
    def "reconciles the remote at resume start"() {
        given:
        store.readTaskRecord(_) >> recordWith(null, null, false)
        branches.fenceParkDelivery(_, _) >> new ParkDeliveryVerdict.Delivered()

        when:
        resume(chain(new ScriptedExecutor([completedRound()])))

        then:
        1 * branches.reconcileRemote(CLONE_DIR, 'PROJ-1', 'resume-start')
    }

    // NG6, FR4: a delivered run has no pending tracker write to protect and no park for another
    // instance to reconcile from, so it stays purely best-effort and never spends the fence's extra
    // refs read. {@code TakeFenceScopeSpec} owns that scope rule for every non-park outcome.
    def "a delivered run runs no delivery fence"() {
        given:
        store.readTaskRecord(_) >> recordWith(null, null, false)

        when:
        def result = resume(chain(new ScriptedExecutor([completedRound()])))

        then:
        0 * branches.fenceParkDelivery(_, _)
        result instanceof TakeResult.Delivered
    }

    // M2, FR4: the park's commit must be delivered to origin BEFORE the tracker announces the park —
    // otherwise another instance reconciles from an origin that lacks the outcome. The ordering is
    // asserted directly: the fence's invocation precedes the tracker write's.
    def "the delivery fence runs before the park's tracker write"() {
        given:
        store.readTaskRecord(_) >> recordWith(null, null, false)

        when:
        resume(parkingChain())

        then: 'FR10: the park\'s durable intent — the outcome commit — is recorded first of all'
        1 * lifecycleStore.recordOutcome('PROJ-1', _ as TaskOutcome.Escalated)

        then: 'the fence is asked next'
        1 * branches.fenceParkDelivery(CLONE_DIR, 'PROJ-1') >> new ParkDeliveryVerdict.Delivered()

        then: 'and only then does the tracker learn about the park'
        1 * tracker.park(REF, ParkReason.ESCALATION, {
            !it.contains('origin is behind this park')
        })
    }

    // FR5, UX2, NFR-O1: an exhausted fence never blocks the park — the tracker write proceeds and
    // the report the human reads carries the one-line replication note.
    def "an exhausted fence parks anyway, with the origin-behind note in the report"() {
        given:
        store.readTaskRecord(_) >> recordWith(null, null, false)
        branches.fenceParkDelivery(_, _) >> new ParkDeliveryVerdict.Undelivered(BEHIND_NOTE)

        when:
        def result = resume(parkingChain())

        then: 'the park still lands, and its report tells the human origin is behind'
        1 * tracker.park(REF, ParkReason.ESCALATION, {
            it.contains('origin is behind this park')
        })
        (result as TakeResult.AwaitingHuman).report().contains('origin is behind this park')
    }

    // FR4, FR5: a DEFERRED park — the orphaned-park reconcile, zero engine rounds — is a park write
    // like any other and gets the same fence. The resume-start reconciliation ahead of it is
    // best-effort and swallows its own failure, so without this the human would be told nothing when
    // origin never received the park the tracker is announcing.
    def "a deferred park's re-post carries the fence's origin-behind note"() {
        given:
        def report = new EscalationReport.AttemptsExhausted(3)
        store.readTaskRecord(_) >> recordWith(new RecordedOutcome.Escalated(report), report, true)
        def executor = new ScriptedExecutor([completedRound()])

        when:
        def result = resume(chain(executor))

        then: 'the fence ran for the deferred park, and its verdict shaped the re-posted report'
        1 * branches.fenceParkDelivery(CLONE_DIR, 'PROJ-1') >> new ParkDeliveryVerdict.Undelivered(BEHIND_NOTE)
        1 * tracker.park(REF, ParkReason.ESCALATION, {
            it.contains('origin is behind this park')
        })

        and: 'still zero engine rounds — the fence is a refs read, not a resumed run'
        executor.requests.isEmpty()
        result instanceof TakeResult.AwaitingHuman
    }

    // FR4: a delivered verdict adds nothing to the deferred park's report — the note appears only
    // when origin is genuinely behind.
    def "a deferred park whose fence reports delivered carries no note"() {
        given:
        def report = new EscalationReport.AttemptsExhausted(3)
        store.readTaskRecord(_) >> recordWith(new RecordedOutcome.Escalated(report), report, true)

        when:
        resume(chain(new ScriptedExecutor([completedRound()])))

        then:
        1 * branches.fenceParkDelivery(CLONE_DIR, 'PROJ-1') >> new ParkDeliveryVerdict.Delivered()
        1 * tracker.park(REF, ParkReason.ESCALATION, {
            !it.contains('origin is behind this park')
        })
    }
}
