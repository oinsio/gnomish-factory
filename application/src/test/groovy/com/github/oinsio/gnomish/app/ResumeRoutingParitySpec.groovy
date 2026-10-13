package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.TaskRepository
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.DeliveredBranchState
import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskRecord
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.git.WorktreeSalvager
import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeProbe
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeRetries
import com.github.oinsio.gnomish.gitobjects.MissingObjectException
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.BindingTrustTable
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.UnaryOperator
import spock.lang.Specification
import spock.lang.TempDir

/**
 * `tracker-take` "One resume routing table for both execution modes", as one identity: the same
 * branch record is resumed through the shared table ({@link TakeDispositionResume}) once over host
 * mechanics and once over container mechanics, and the two runs are compared — the tracker effect
 * each produced and the engine rounds each paid for. {@code TakeResumeRoutingSpec} and
 * {@code TakeContainerResumeRoutingSpec} prove each route in each mode; this spec proves the two
 * modes agree, on port fakes only.
 *
 * <p>FR1, FR2, NFR-R4 of add-serve-sandbox-lifecycle.
 */
class ResumeRoutingParitySpec extends Specification implements RunChainFakes {

    private static final TaskState RECORDED = TaskState.atStageStart('build')

    @TempDir
    Path tempDir

    // FR1, NFR-R4: one route per record, whatever the execution mode.
    // Scenario: tracker/tracker-take / Every recorded outcome routes alike in host and container mode
    def "#label takes the same route in host and container mode"() {
        when:
        def host = resumeInHost(record)
        def container = resumeInContainer(record)

        then: 'the same tracker effect and the same engine rounds in both modes'
        host == container
        host == [effects: effects, rounds: rounds]

        where:
        label | record || effects | rounds
        'a delivered tip with its finish owed' | null || ['finish'] | 0
        'a park with its tracker write owed' | recordWith(escalated(exhausted()), exhausted(), true) || ['park ESCALATION'] | 0
        'an escalation-kind park' | recordWith(escalated(question()), question(), false) || ['park ESCALATION'] | 0
        'an interrupted visit (no outcome)' | recordWith(null, null, false) || ['finish'] | 1
    }

    private static EscalationReport exhausted() {
        new EscalationReport.AttemptsExhausted(3)
    }

    private static EscalationReport question() {
        new EscalationReport.DecisionNeeded(UntrustedText.agent('which way?'), [])
    }

    private static RecordedOutcome escalated(EscalationReport report) {
        new RecordedOutcome.Escalated(report)
    }

    /** The tracker writes a resume made, in order, as {@code finish} / {@code park <reason>}. */
    private Tracker recordingTracker(List<String> effects) {
        Stub(Tracker) {
            fetchTask(_) >> heldByUs()
            collectDecisions(_) >> []
            finish(_, _) >> { effects << 'finish' }
            park(_, _, _) >> { ref, reason, report ->
                effects << "park ${reason}".toString()
            }
            release(_) >> { effects << 'release' }
        }
    }

    private TaskBranchGit branches() {
        Stub(TaskBranchGit) {
            locate(_, _) >> new BranchLocation.Local('refs/heads/gnomish/PROJ-1')
            classifyShape(_, _) >> new BranchShape.InProgress()
            ensureLocalTaskBranch(_, _) >> true
            fenceParkDelivery(_, _) >> new ParkDeliveryVerdict.Delivered()
            readDelivered(_, _) >> new DeliveredBranchState(
                    new TaskContext('PROJ-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), []), RECORDED)
        }
    }

    private Map resumeInHost(TaskRecord record) {
        def effects = []
        def tracker = recordingTracker(effects)
        def executor = new ScriptedExecutor([completedRound()])
        def worktree = Files.createDirectories(tempDir.resolve('worktrees').resolve('PROJ-1'))
        def store = Stub(TaskStoreGit) {
            taskRepository(_) >> Stub(TaskLifecycleStore)
            attemptPersistence(_, _) >> new InMemoryAttemptPersistence()
            readRecordedState(_, _) >> Optional.of(RECORDED)
            readTaskRecord(_, _) >> Optional.ofNullable(record)
        }
        def worktrees = Stub(TaskWorktreeGit) {
            ensureWorktree(_, _, _) >> worktree
            salvage(_) >> Stub(WorktreeSalvager)
        }
        def git = new TaskGit(store, branches(), worktrees, UnaryOperator.identity(), resumingBaseRefGit(), new ClaimEpochBook())
        def clone = RegisteredCloneFixture.unregistered(tempDir.resolve('home'), CLONE_DIR)
        def runner = new TakeResumeRunner(slotWiring(assemblyRunning(executor), git, tracker, clone))
        def mechanics = new HostResumeMechanics(runner, git, clone, completingPipeline())
        resume(new TakeDispositionResume(mechanics, new TakeDecisionResume(mechanics), git, VirtualTimeRetries.terminalWrite()),
                tracker)
        [effects: effects, rounds: executor.requests.size()]
    }

    private Map resumeInContainer(TaskRecord record) {
        def effects = []
        def tracker = recordingTracker(effects)
        def executor = new ScriptedExecutor([completedRound()])
        def support = Stub(SandboxRunSupport) {
            readTaskJson() >> {
                if (record == null) {
                    throw new MissingObjectException('no blob at .gnomish-task/task.json')
                }
                record
            }
            readFinalState() >> RECORDED
            taskRepository() >> Stub(TaskRepository)
            persistence() >> new InMemoryAttemptPersistence()
            workspace() >> new FakeWorkspace()
            pieces(_) >> new SandboxRunPieces(null, null, null, null, null, null, null)
            pendingVerification() >> Optional.empty()
        }
        def git = new TaskGit(Stub(TaskStoreGit), branches(), Stub(TaskWorktreeGit), UnaryOperator.identity(),
                resumingBaseRefGit(), new ClaimEpochBook())
        def selector = new SandboxModeSelector(new BindingProperties(null, [:]),
        new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null),
        AdapterBindingRegistry.ratified([], BindingTrustTable.firstParty()), {
            -> false
        } as ContainerRuntimeProbe)
        def containerTakeSupport = new ContainerTakeSupport(selector, { cloneDir, taskId, segments, definition, creds ->
            support
        } as ContainerSupportFactory)
        def runner = new TakeContainerResumeRunner(slotWiring(assemblyRunning(executor), git, tracker, CLONE, containerTakeSupport))
        def mechanics = new ContainerResumeMechanics(runner, [] as List<Segment>, completingPipeline())
        resume(new TakeDispositionResume(mechanics, new TakeDecisionResume(mechanics), git, VirtualTimeRetries.terminalWrite()),
                tracker)
        [effects: effects, rounds: executor.requests.size()]
    }

    private void resume(TakeDispositionResume chain, Tracker tracker) {
        chain.resumeExisting(takeOrder(heldByUs(), tracker, runOrder(completingPipeline())), new BranchShape.InProgress())
    }
}
