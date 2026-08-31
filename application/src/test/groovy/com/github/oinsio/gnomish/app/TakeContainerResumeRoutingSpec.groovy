package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.TaskRepository
import com.github.oinsio.gnomish.app.port.git.DeliveredBranchState
import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskRecord
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.gitobjects.MissingObjectException
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.BindingTrustTable
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import java.time.Instant
import spock.lang.Specification


/**
 * FR1, NFR-R4 of add-serve-sandbox-lifecycle: the SHARED resume routing table ({@code
 * TakeDispositionResume}) driven over {@link ContainerResumeMechanics}, on port fakes only — no
 * Docker, no git subprocess, mirroring {@link RunChainFakes}'s discipline. The host twin of this
 * spec is {@code TakeResumeRoutingSpec}; together they are the contract-test pair design D8 puts in
 * place of the two mirrored routing tables that preceded it, so every route is proven in BOTH
 * execution modes. Covers all of them: a delivered-and-cleaned branch reconciled to a deferred
 * finish, an orphaned Escalated and an orphaned Paused park, an interrupted (null-outcome) resume,
 * {@code --discard-work}, and the three escalation-dialog branches.
 *
 * <p>Implements FR1, NFR-R4 of add-serve-sandbox-lifecycle; FR9, FR10, D3, D10 of add-tracker-port
 * and add-claim-heartbeat.
 */
class TakeContainerResumeRoutingSpec extends Specification implements RunChainFakes {

    private static Workspace workspace() {
        {} as Workspace
    }

    private static SandboxRunPieces pieces() {
        new SandboxRunPieces(null, null, null, null, null, null, null)
    }

    private TakeDispositionResume disposition(TaskGit git) {
        def containerSupportFactory = { cloneDir, taskId, segments, sandboxProps, factoryProps, definition, creds ->
            builtSupport
        } as ContainerSupportFactory
        def containerTakeSupport = new ContainerTakeSupport(
                new FactoryProperties(null, null, null, null, null, null),
                new BindingProperties(null, [:]),
                new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null),
                AdapterBindingRegistry.ratified([], BindingTrustTable.firstParty()),
                { false },
                containerSupportFactory)
        def resumeRunner = new TakeContainerResumeRunner(
                assemblyRunning(new ScriptedExecutor([completedRound()])), git, containerTakeSupport,
                new AbortHandler(tracker, FIXED_CLOCK), 3, [], new ClaimLossFlag(), 'taskId')
        def mechanics = new ContainerResumeMechanics(
                resumeRunner, [] as List<Segment>, completingPipeline())
        new TakeDispositionResume(mechanics, new TakeDecisionResume(mechanics), git)
    }

    SandboxRunSupport builtSupport
    Tracker tracker = Mock(Tracker)

    private static TaskContext taskContext(String taskId = 'PROJ-1') {
        new TaskContext(taskId, 'title', 'body', [])
    }

    private TaskGit gitWith(TaskBranchGit branches) {
        new TaskGit(Stub(TaskStoreGit), branches, Stub(TaskWorktreeGit))
    }

    // FR3 of fix-lifecycle-push: resume start is a touchpoint — once the local branch is reconciled
    // into the clone, origin is reconciled up to it, delivering a push an earlier instance lost.
    def "reconciles the remote at resume start, right after the local branch is ensured"() {
        given:
        def branches = Mock(TaskBranchGit)
        builtSupport = Mock(SandboxRunSupport) {
            readTaskJson() >> {
                throw new MissingObjectException('no blob at .gnomish-task/task.json')
            }
        }
        tracker.fetchTask(_) >> heldByUs()
        branches.readDelivered(CLONE_DIR, 'PROJ-1') >> new DeliveredBranchState(
                taskContext(), TaskState.atStageStart('build'))

        when:
        disposition(gitWith(branches)).resumeExisting(
                CLONE_DIR, new BranchShape.InProgress(), RunArguments.InteractiveMode.NONE, false, 'PROJ-1', tracker, REF, INSTANCE)

        then:
        1 * branches.ensureLocalTaskBranch(CLONE_DIR, 'PROJ-1') >> true

        then:
        1 * branches.reconcileRemote(CLONE_DIR, 'PROJ-1', 'resume-start')
    }

    // FR1 of add-serve-sandbox-lifecycle, FR10/D10/NFR-C1 of add-claim-heartbeat: the branch tip
    // carries no .gnomish-task/ at all — the Completed cleanup commit stripped it — so the work was
    // delivered while the tracker finish never landed. The deferred finish is posted from the
    // branch's own delivered history with ZERO engine rounds and no environment touched at all.
    // This is the route container mode lacked before the routing table was unified (design D8).
    def "a delivered-and-cleaned branch posts the deferred finish with zero engine rounds"() {
        given:
        def branches = Mock(TaskBranchGit) {
            ensureLocalTaskBranch(_, _) >> true
            readDelivered(CLONE_DIR, 'PROJ-1') >> new DeliveredBranchState(
                    taskContext(), TaskState.atStageStart('build'))
        }
        builtSupport = Mock(SandboxRunSupport) {
            readTaskJson() >> {
                throw new MissingObjectException('no blob at .gnomish-task/task.json')
            }
        }
        tracker.fetchTask(_) >> heldByUs()

        when:
        def result = disposition(gitWith(branches)).resumeExisting(
                CLONE_DIR, new BranchShape.InProgress(), RunArguments.InteractiveMode.NONE, false, 'PROJ-1', tracker, REF, INSTANCE)

        then: 'the finish is posted from branch history, and no box is reattached or salvaged'
        1 * tracker.finish(REF, _)
        0 * builtSupport.reattachFor(_)
        0 * builtSupport.salvageLeftovers(_)
        0 * builtSupport.readFinalState()
        result instanceof TakeResult.Delivered
    }

    // FR1: only the "cleanup already ran" shape reconciles. Any other bare-object read failure is a
    // genuine fault and must surface rather than be mistaken for a delivered branch.
    def "lets an unrelated bare-object read failure propagate"() {
        given:
        def branches = Mock(TaskBranchGit) {
            ensureLocalTaskBranch(_, _) >> true
        }
        builtSupport = Mock(SandboxRunSupport) {
            readTaskJson() >> {
                throw new IllegalStateException('object store on fire')
            }
        }

        when:
        disposition(gitWith(branches)).resumeExisting(
                CLONE_DIR, new BranchShape.InProgress(), RunArguments.InteractiveMode.NONE, false, 'PROJ-1', tracker, REF, INSTANCE)

        then:
        def ex = thrown(IllegalStateException)
        ex.message == 'object store on fire'
        0 * tracker.finish(_, _)
    }

    // FR9: outcome null (process died mid-visit) reattaches, salvages, and runs the engine once to
    // a terminal Completed/Delivered result.
    def "an interrupted (null-outcome) resume reattaches, salvages, and finishes on the tracker"() {
        given:
        def branches = Mock(TaskBranchGit) {
            ensureLocalTaskBranch(_, _) >> true
        }
        builtSupport = Mock(SandboxRunSupport) {
            readTaskJson() >> new TaskRecord(taskContext(), 'base', Instant.EPOCH, null, null, false)
            readFinalState() >> TaskState.atStageStart('build')
            persistence() >> new InMemoryAttemptPersistence()
            workspace() >> workspace()
            pieces(_) >> pieces()
            pendingVerification() >> Optional.empty()
        }
        tracker.fetchTask(_) >> heldByUs()

        when:
        def result = disposition(gitWith(branches)).resumeExisting(
                CLONE_DIR, new BranchShape.InProgress(), RunArguments.InteractiveMode.NONE, false, 'PROJ-1', tracker, REF, INSTANCE)

        then:
        1 * branches.harden(CLONE_DIR)
        1 * builtSupport.reattachFor('build')
        1 * builtSupport.salvageLeftovers('PROJ-1')
        1 * tracker.finish(REF, _)
        result instanceof TakeResult.Delivered
    }

    // NFR-R4: --discard-work disposes the existing environment instead of reattaching/salvaging.
    def "--discard-work disposes the existing environment instead of salvaging"() {
        given:
        def branches = Mock(TaskBranchGit) {
            ensureLocalTaskBranch(_, _) >> true
        }
        builtSupport = Mock(SandboxRunSupport) {
            readTaskJson() >> new TaskRecord(taskContext(), 'base', Instant.EPOCH, null, null, false)
            readFinalState() >> TaskState.atStageStart('build')
            persistence() >> new InMemoryAttemptPersistence()
            workspace() >> workspace()
            pieces(_) >> pieces()
            pendingVerification() >> Optional.empty()
        }
        tracker.fetchTask(_) >> heldByUs()

        when:
        disposition(gitWith(branches)).resumeExisting(
                CLONE_DIR, new BranchShape.InProgress(), RunArguments.InteractiveMode.NONE, true, 'PROJ-1', tracker, REF, INSTANCE)

        then:
        1 * builtSupport.disposeExistingEnvironment()
        0 * builtSupport.reattachFor(_)
        0 * builtSupport.salvageLeftovers(_)
    }

    // FR10, D10, NFR-C1 of add-claim-heartbeat: a branch whose park was never confirmed delivered
    // (trackerWritePending) is reconciled with zero engine rounds — no reattach, no salvage.
    def "an orphaned Escalated park is delivered with zero engine rounds"() {
        given:
        def branches = Mock(TaskBranchGit) {
            ensureLocalTaskBranch(_, _) >> true
            // FR4 of fix-lifecycle-push: a deferred park runs the same delivery fence a fresh
            // one does; a sealed verdict has no Spock dummy, so state the delivered case.
            fenceParkDelivery(_, _) >> new ParkDeliveryVerdict.Delivered()
        }
        def report = new EscalationReport.AttemptsExhausted(3)
        builtSupport = Mock(SandboxRunSupport) {
            readTaskJson() >> new TaskRecord(
            taskContext(), 'base', Instant.EPOCH, new RecordedOutcome.Escalated(report), report, true)
            readFinalState() >> TaskState.atStageStart('build')
        }
        tracker.fetchTask(_) >> heldByUs()

        when:
        def result = disposition(gitWith(branches)).resumeExisting(
                CLONE_DIR, new BranchShape.InProgress(), RunArguments.InteractiveMode.NONE, false, 'PROJ-1', tracker, REF, INSTANCE)

        then:
        0 * builtSupport.reattachFor(_)
        0 * builtSupport.salvageLeftovers(_)
        0 * builtSupport.sweepOrphans()
        1 * tracker.park(REF, ParkReason.ESCALATION, _)

        then: 'FR10 of harden-task-branch-contract: the receipt clears the pending marker in-box'
        1 * builtSupport.confirmTerminalWrite()

        and:
        result instanceof TakeResult.AwaitingHuman
    }

    // FR9, FR10, NFR-C1 of harden-task-branch-contract: the container twin of the host
    // CompletedUncleaned route (`TakeResumeShapeTailSpec`). The tip records Completed with its
    // envelope intact — the kill window between the outcome commit and the tracker finish — so the
    // deferred finish is written and the in-box cleanup commit follows it, with no engine round and
    // no environment reattached.
    def "FR9: a CompletedUncleaned tip finishes in-box without re-entering the engine"() {
        given:
        def branches = Mock(TaskBranchGit) {
            ensureLocalTaskBranch(_, _) >> true
        }
        builtSupport = Mock(SandboxRunSupport) {
            readTaskJson() >> new TaskRecord(
            taskContext(), 'base', Instant.EPOCH, new RecordedOutcome.Completed(), null, false)
            readFinalState() >> TaskState.atStageStart('build')
        }
        tracker.fetchTask(_) >> heldByUs()

        when:
        def result = disposition(gitWith(branches)).resumeExisting(
                CLONE_DIR, new BranchShape.CompletedUncleaned(), RunArguments.InteractiveMode.NONE, false,
                'PROJ-1', tracker, REF, INSTANCE)

        then: 'the deferred finish is written, and only then is the envelope stripped'
        1 * tracker.finish(REF, _)

        then:
        1 * builtSupport.finishCleanup()

        and: 'no paid round, no environment touched'
        0 * builtSupport.reattachFor(_)
        0 * builtSupport.salvageLeftovers(_)
        result instanceof TakeResult.Delivered
    }

    // FR10, D10, NFR-C1 of add-claim-heartbeat: the Paused twin of the Escalated orphaned-park
    // scenario above — deliverPark's other branch, zero engine rounds either way.
    def "an orphaned Paused park is delivered with zero engine rounds"() {
        given:
        def branches = Mock(TaskBranchGit) {
            ensureLocalTaskBranch(_, _) >> true
            // FR4 of fix-lifecycle-push: a deferred park runs the same delivery fence a fresh
            // one does; a sealed verdict has no Spock dummy, so state the delivered case.
            fenceParkDelivery(_, _) >> new ParkDeliveryVerdict.Delivered()
        }
        builtSupport = Mock(SandboxRunSupport) {
            readTaskJson() >> new TaskRecord(
            taskContext(), 'base', Instant.EPOCH, new RecordedOutcome.Paused('build'), null, true)
            readFinalState() >> TaskState.atStageStart('build')
        }
        tracker.fetchTask(_) >> heldByUs()

        when:
        def result = disposition(gitWith(branches)).resumeExisting(
                CLONE_DIR, new BranchShape.InProgress(), RunArguments.InteractiveMode.NONE, false, 'PROJ-1', tracker, REF, INSTANCE)

        then:
        0 * builtSupport.reattachFor(_)
        0 * builtSupport.salvageLeftovers(_)
        1 * tracker.park(REF, ParkReason.CHECKPOINT, _)
        result instanceof TakeResult.AwaitingHuman
    }

    // FR12, D3: an AttemptsExhausted escalation with no pending human reply resumes on the return
    // alone — the engine runs once, resetting the attempt counter.
    def "an AttemptsExhausted escalation with no pending reply resumes on the return alone"() {
        given:
        def branches = Mock(TaskBranchGit) {
            ensureLocalTaskBranch(_, _) >> true
        }
        def report = new EscalationReport.AttemptsExhausted(3)
        builtSupport = Mock(SandboxRunSupport) {
            readTaskJson() >> new TaskRecord(
            taskContext(), 'base', Instant.EPOCH, new RecordedOutcome.Escalated(report), report, false)
            readFinalState() >> TaskState.atStageStart('build')
            persistence() >> new InMemoryAttemptPersistence()
            workspace() >> workspace()
            pieces(_) >> pieces()
            pendingVerification() >> Optional.empty()
        }
        tracker.collectDecisions(REF) >> []
        tracker.fetchTask(_) >> heldByUs()

        when:
        def result = disposition(gitWith(branches)).resumeExisting(
                CLONE_DIR, new BranchShape.InProgress(), RunArguments.InteractiveMode.NONE, false, 'PROJ-1', tracker, REF, INSTANCE)

        then: 'no reattach/salvage — resumeWithDecision skips the null-outcome salvage step entirely'
        0 * builtSupport.reattachFor(_)
        1 * tracker.finish(REF, _)
        result instanceof TakeResult.Delivered
    }

    // FR12: a DecisionNeeded escalation with a fresh human reply is acked, then resumed with it —
    // the reply text is appended as a decision over the container task repository (FR12).
    def "a DecisionNeeded escalation with a pending reply is acknowledged and resumed"() {
        given:
        def branches = Mock(TaskBranchGit) {
            ensureLocalTaskBranch(_, _) >> true
        }
        def report = new EscalationReport.DecisionNeeded('which way?', [])
        def repository = Mock(TaskRepository)
        builtSupport = Mock(SandboxRunSupport) {
            readTaskJson() >> new TaskRecord(
            taskContext(), 'base', Instant.EPOCH, new RecordedOutcome.Escalated(report), report, false)
            readFinalState() >> TaskState.atStageStart('build')
            persistence() >> new InMemoryAttemptPersistence()
            workspace() >> workspace()
            pieces(_) >> pieces()
            pendingVerification() >> Optional.empty()
            taskRepository() >> repository
        }
        def reply = new HumanReply('go left', Instant.EPOCH)
        tracker.collectDecisions(REF) >> [reply]
        tracker.fetchTask(_) >> heldByUs()

        when:
        def result = disposition(gitWith(branches)).resumeExisting(
                CLONE_DIR, new BranchShape.InProgress(), RunArguments.InteractiveMode.NONE, false, 'PROJ-1', tracker, REF, INSTANCE)

        then: 'FR17, D12 of harden-task-branch-contract: the kept box goes first — its clone could never learn of the decision commit'
        1 * builtSupport.disposeExistingEnvironment()

        then: 'FR12: the decision is durable on the branch before the acknowledge posts'
        1 * repository.appendDecision('PROJ-1', {
            it.body() == 'go left' && it.stage() == 'build'
        }, _)

        then:
        1 * tracker.acknowledgeDecision(REF, 'go left')
        1 * tracker.finish(REF, _)
        result instanceof TakeResult.Delivered
    }

    // FR12, FR13: a DecisionNeeded escalation with NO pending reply re-parks, restating the
    // question, instead of resuming (the distinct-from-AttemptsExhausted branch of the same switch).
    def "a DecisionNeeded escalation with no pending reply re-parks restating the question"() {
        given:
        def branches = Mock(TaskBranchGit) {
            ensureLocalTaskBranch(_, _) >> true
        }
        def report = new EscalationReport.DecisionNeeded('which way?', [])
        builtSupport = Mock(SandboxRunSupport) {
            readTaskJson() >> new TaskRecord(
            taskContext(), 'base', Instant.EPOCH, new RecordedOutcome.Escalated(report), report, false)
            readFinalState() >> TaskState.atStageStart('build')
        }
        tracker.collectDecisions(REF) >> []
        tracker.fetchTask(_) >> heldByUs()

        when:
        def result = disposition(gitWith(branches)).resumeExisting(
                CLONE_DIR, new BranchShape.InProgress(), RunArguments.InteractiveMode.NONE, false, 'PROJ-1', tracker, REF, INSTANCE)

        then:
        1 * tracker.park(REF, ParkReason.ESCALATION, {
            it.contains('which way?')
        })
        0 * tracker.finish(_, _)
        result instanceof TakeResult.AwaitingHuman
    }
}
