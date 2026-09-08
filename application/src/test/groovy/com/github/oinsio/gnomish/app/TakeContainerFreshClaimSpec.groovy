package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.TaskRepository
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.pipeline.BoundTaskTier
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.BindingTrustTable
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
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
        ContainerSupportFactory factory = { cloneDir, taskId, segments, sandboxProps, factoryProps, definition, creds ->
            support
        }
        new ContainerTakeSupport(
                new FactoryProperties(null, null, null, null, null, null),
                new BindingProperties(null, [:]),
                new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null),
                AdapterBindingRegistry.ratified([], BindingTrustTable.firstParty()),
                { false },
                factory)
    }

    // FR1, FR2: the task branch is created factory-side over the SandboxRunSupport's own task
    // repository (no worktree), hardening runs first, and the engine runs once to a terminal
    // Completed/Delivered result through the container assembly.
    def "creates the task over the sandbox task repository, hardening first, and runs the engine once"() {
        given:
        def tracker = Mock(Tracker)
        def branches = Mock(TaskBranchGit)
        def git = new TaskGit(
                Stub(TaskStoreGit), branches, Stub(TaskWorktreeGit),
                UnaryOperator.identity(), refreshingBaseRefGit())
        def repository = Mock(TaskRepository)
        def support = Stub(SandboxRunSupport) {
            taskRepository() >> repository
            persistence() >> new InMemoryAttemptPersistence()
            workspace() >> new FakeWorkspace()
            pieces(_) >> new SandboxRunPieces(null, null, null, null, null, null, null)
        }
        tracker.fetchTask(_) >> heldByUs()

        when:
        def result = TakeContainerFreshClaim.claim(
                assemblyRunning(new ScriptedExecutor([completedRound()])), git, containerTakeSupport(support),
                [] as List<Segment>, new AbortHandler(tracker, FIXED_CLOCK), 3, [], CLONE_DIR, null,
                completingPipeline(), RunArguments.InteractiveMode.NONE, readyTask(), tracker, INSTANCE,
                new ClaimLossFlag(), DEFAULT_TRUSTED_BASE)

        then: 'the clone is hardened before the branch is created (mirrors ContainerGitModeRunner)'
        1 * branches.harden(CLONE_DIR)

        then: 'the branch is created from the trusted-tier default branch'
        1 * repository.createTask({ it.taskId() == 'PROJ-1' }, 'main', _, _)

        and: 'the engine really ran the stage, and the run finished on the tracker'
        1 * tracker.finish(_, _)
        result instanceof TakeResult.Delivered
    }

    // FR9: the explicit --base is passed through to the sandbox task repository exactly as the
    // host fresh-claim path passes it to the host one.
    def "passes the explicit --base through to the sandbox task repository"() {
        given:
        def tracker = Mock(Tracker)
        def git = new TaskGit(
                Stub(TaskStoreGit), Stub(TaskBranchGit), Stub(TaskWorktreeGit),
                UnaryOperator.identity(), refreshingBaseRefGit())
        def repository = Mock(TaskRepository)
        def support = Stub(SandboxRunSupport) {
            taskRepository() >> repository
            persistence() >> new InMemoryAttemptPersistence()
            workspace() >> new FakeWorkspace()
            pieces(_) >> new SandboxRunPieces(null, null, null, null, null, null, null)
        }
        tracker.fetchTask(_) >> heldByUs('PROJ-9')

        when:
        TakeContainerFreshClaim.claim(
                assemblyRunning(new ScriptedExecutor([completedRound()])), git, containerTakeSupport(support),
                [] as List<Segment>, new AbortHandler(tracker, FIXED_CLOCK), 3, [], CLONE_DIR, 'release/1.2',
                completingPipeline(), RunArguments.InteractiveMode.NONE, readyTask('PROJ-9'), tracker, INSTANCE,
                new ClaimLossFlag(), DEFAULT_TRUSTED_BASE)

        then:
        1 * repository.createTask({
            it.taskId() == 'PROJ-9'
        }, 'release/1.2', _, _)
        1 * tracker.finish(_, _)
    }

    // FR13 of add-base-ref-resolution: mirrors TakeFreshClaimSpec's equivalent row — once the base
    // is resolved and refreshed, an invalid law at that base parks the task instead of creating
    // anything, exercised here through TakeContainerFreshClaim's own claimAt call site.
    def "parks the task when the resolved base's own law fails to load, creating nothing"() {
        given:
        def tracker = Mock(Tracker)
        def git = new TaskGit(
                Stub(TaskStoreGit), Mock(TaskBranchGit), Stub(TaskWorktreeGit),
                UnaryOperator.identity(), refreshingBaseRefGit())
        def repository = Mock(TaskRepository)
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
        ] as RunAssembly

        when:
        def result = TakeContainerFreshClaim.claim(
                invalidAssembly, git, containerTakeSupport(support),
                [] as List<Segment>, new AbortHandler(tracker, FIXED_CLOCK), 3, [], CLONE_DIR, null,
                completingPipeline(), RunArguments.InteractiveMode.NONE, readyTask(), tracker, INSTANCE,
                new ClaimLossFlag(), DEFAULT_TRUSTED_BASE)

        then: 'parked, never having created the branch or reached the engine'
        0 * repository.createTask(*_)
        1 * tracker.park(REF, _, _)
        result instanceof TakeResult.AwaitingHuman
    }
}
