package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.git.BasePin
import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.pipeline.BoundTaskTier
import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeProbe
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.BindingTrustTable
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import java.nio.file.Path
import java.util.function.UnaryOperator
import spock.lang.Specification

/**
 * The container-mode counterpart of {@code TakeFreshClaimSpec} (FR1 of add-serve-sandbox-
 * lifecycle): drives {@link TakeContainerFreshClaim} over a {@link SandboxRunSupport} stub built
 * by a scripted {@link ContainerSupportFactory} — no Docker, no git subprocess, no composition
 * root — mirroring the port-fake discipline {@link RunChainFakes} already applies to the host
 * fresh-claim path.
 *
 * <p>Implements FR1, FR2 of add-serve-sandbox-lifecycle; FR9, FR11, D3 of add-tracker-port.
 */
class TakeContainerFreshClaimSpec extends Specification implements RunChainFakes {

    private static ContainerTakeSupport containerTakeSupport(SandboxRunSupport support) {
        ContainerSupportFactory factory = { cloneDir, taskId, segments, definition, creds ->
            support
        }
        new ContainerTakeSupport(new SandboxModeSelector(
                        new BindingProperties(null, [:]),
                        new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null),
                        AdapterBindingRegistry.ratified([], BindingTrustTable.firstParty()),
                        { -> false } as ContainerRuntimeProbe),
                factory)
    }

    // FR1, FR2: the task branch is created factory-side over the SandboxRunSupport's own task
    // repository (no worktree), the ref-backend check runs first, and the engine runs once to a terminal
    // Completed/Delivered result through the container assembly.
    def "creates the task over the sandbox task repository, ref-backend check first, and runs the engine once"() {
        given:
        def tracker = Mock(Tracker)
        def branches = Mock(TaskBranchGit)
        def git = new TaskGit(
                Stub(TaskStoreGit), branches, Stub(TaskWorktreeGit),
                UnaryOperator.identity(), refreshingBaseRefGit(), new ClaimEpochBook())
        def repository = Mock(TaskLifecycleStore)
        def support = Stub(SandboxRunSupport) {
            taskRepository() >> repository
            persistence() >> new InMemoryAttemptPersistence()
            workspace() >> new FakeWorkspace()
            pieces(_) >> new SandboxRunPieces(null, null, null, null, null, null, null)
        }
        tracker.fetchTask(_) >> heldByUs()

        when:
        def result = new TakeContainerFreshClaim(slotWiring(
                        assemblyRunning(new ScriptedExecutor([completedRound()])), git, tracker, CLONE, containerTakeSupport(support))).claim(
                takeOrder(readyTask(), tracker, runOrder(completingPipeline())),
                [] as List<Segment>)

        then: 'FR6: the clone\'s ref backend is checked before the branch is created (mirrors ContainerGitModeRunner)'
        1 * branches.requireRefFormat(CLONE_DIR)

        then: 'the branch is created from the trusted-tier default branch'
        1 * repository.createTask({
            it.taskId() == 'PROJ-1'
        }, LAW_COMMIT, DEFAULT_BRANCH_PIN, _)

        then: 'FR8 of fix-terminal-receipt-convergence: the created branch is answered local by the adoption owner'
        1 * branches.locate(CLONE_DIR, 'PROJ-1') >> new BranchLocation.Local('refs/heads/gnomish/PROJ-1')
        1 * branches.adopt(CLONE_DIR, 'PROJ-1', new BranchLocation.Local('refs/heads/gnomish/PROJ-1')) >>
                new BranchAdoption.AlreadyLocal('PROJ-1')

        and: 'the engine really ran the stage, and the run finished on the tracker'
        1 * tracker.finish(_, _)
        result instanceof TakeResult.Delivered

        then: 'FR4 of fix-terminal-receipt-convergence: the delivered branch is cleaned behind the finish'
        1 * branches.classifyShape(CLONE_DIR, 'PROJ-1') >> new BranchShape.CompletedUncleaned()
        1 * repository.finishCleanup('PROJ-1')
    }

    // D6 of introduce-take-order (FR13 of add-base-ref-resolution): the container twin re-binds the
    // order to the task's own law exactly as the host one does — the startup definition here names
    // a stage the task tier does not have.
    def "D6: runs the engine under the task's own law, not the startup definition the order was built with"() {
        given:
        def tracker = Mock(Tracker)
        def git = new TaskGit(
                Stub(TaskStoreGit), freshBranches(), Stub(TaskWorktreeGit),
                UnaryOperator.identity(), refreshingBaseRefGit(), new ClaimEpochBook())
        def support = Stub(SandboxRunSupport) {
            taskRepository() >> Mock(TaskLifecycleStore)
            persistence() >> new InMemoryAttemptPersistence()
            workspace() >> new FakeWorkspace()
            pieces(_) >> new SandboxRunPieces(null, null, null, null, null, null, null)
        }
        def executor = new ScriptedExecutor([completedRound()])
        tracker.fetchTask(_) >> heldByUs()

        when:
        def result = new TakeContainerFreshClaim(slotWiring(
                        assemblyRunning(executor), git, tracker, CLONE, containerTakeSupport(support))).claim(
                takeOrder(readyTask(), tracker, runOrder(startupOnlyPipeline())),
                [] as List<Segment>)

        then:
        executor.requests*.stage()*.name() == ['build']
        result instanceof TakeResult.Delivered
    }

    // FR9: the explicit --base is passed through to the sandbox task repository exactly as the
    // host fresh-claim path passes it to the host one.
    def "passes the explicit --base through to the sandbox task repository"() {
        given:
        def tracker = Mock(Tracker)
        def git = new TaskGit(
                Stub(TaskStoreGit), Stub(TaskBranchGit) {
                    locate(_, _) >> { Path clone, String taskId ->
                        new BranchLocation.Local('refs/heads/gnomish/' + taskId)
                    }
                    adopt(_, _, _) >> { Path clone, String taskId, BranchLocation location ->
                        new BranchAdoption.AlreadyLocal(taskId)
                    }
                    classifyShape(_, _) >> new BranchShape.Delivered()
                }, Stub(TaskWorktreeGit),
                UnaryOperator.identity(), refreshingBaseRefGit(), new ClaimEpochBook())
        def repository = Mock(TaskLifecycleStore)
        def support = Stub(SandboxRunSupport) {
            taskRepository() >> repository
            persistence() >> new InMemoryAttemptPersistence()
            workspace() >> new FakeWorkspace()
            pieces(_) >> new SandboxRunPieces(null, null, null, null, null, null, null)
        }
        tracker.fetchTask(_) >> heldByUs('PROJ-9')

        when:
        new TakeContainerFreshClaim(slotWiring(
                        assemblyRunning(new ScriptedExecutor([completedRound()])), git, tracker, CLONE, containerTakeSupport(support))).claim(
                takeOrder(readyTask('PROJ-9'), tracker,
                new RunOrder(CLONE_DIR, 'release/1.2', completingPipeline(), false)),
                [] as List<Segment>)

        then:
        1 * repository.createTask({
            it.taskId() == 'PROJ-9'
        }, LAW_COMMIT, new BasePin('release/1.2', BaseRefKind.BRANCH, BaseRule.EXPLICIT_ARGUMENT), _)
        1 * tracker.finish(_, _)
    }

    // FR13 of add-base-ref-resolution: mirrors TakeFreshClaimSpec's equivalent row — once the base
    // is resolved and refreshed, an invalid law at that base parks the task instead of creating
    // anything, exercised here through TakeContainerFreshClaim's own claimAt call site.
    // Scenario: tracker/tracker-take / A task-tier law load error parks the task
    def "parks the task when the resolved base's own law fails to load, creating nothing"() {
        given:
        def tracker = Mock(Tracker)
        def git = new TaskGit(
                Stub(TaskStoreGit), freshBranches(), Stub(TaskWorktreeGit),
                UnaryOperator.identity(), refreshingBaseRefGit(), new ClaimEpochBook())
        def repository = Mock(TaskLifecycleStore)
        def support = Stub(SandboxRunSupport) {
            taskRepository() >> repository
        }
        def errors = [
            new ConfigError('config.yaml', 'pipeline', 'broken pipeline.yaml')
        ]
        def invalidAssembly = [
            bindTaskTier: { binding ->
                new BoundTaskTier(new LoadOutcome.Invalid(errors), LAW_COMMIT)
            },
            timeEquipment: { -> VirtualTimeEquipment.create() },
        ] as RunAssembly

        when:
        def result = new TakeContainerFreshClaim(slotWiring(
                        invalidAssembly, git, tracker, CLONE, containerTakeSupport(support))).claim(
                takeOrder(readyTask(), tracker, runOrder(completingPipeline())),
                [] as List<Segment>)

        then: 'parked, never having created the branch or reached the engine'
        0 * repository.createTask(*_)
        1 * tracker.park(REF, _, _)
        result instanceof TakeResult.AwaitingHuman
    }

    /**
     * The task-branch port of a fresh claim: the adoption owner answers the created branch already
     * local, and the delivered cleanup reads an already-cleaned tip.
     */
    private TaskBranchGit freshBranches() {
        Stub(TaskBranchGit) {
            classifyShape(_, _) >> new BranchShape.Delivered()
            locate(_, _) >> { Path clone, String taskId ->
                new BranchLocation.Local('refs/heads/gnomish/' + taskId)
            }
            adopt(_, _, _) >> { Path clone, String taskId, BranchLocation location ->
                new BranchAdoption.AlreadyLocal(taskId)
            }
        }
    }
}
