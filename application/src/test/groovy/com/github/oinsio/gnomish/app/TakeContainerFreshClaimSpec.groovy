package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.TaskRepository
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.BindingTrustTable
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
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
        def git = new TaskGit(Stub(TaskStoreGit), branches, Stub(TaskWorktreeGit))
        def repository = Mock(TaskRepository)
        def support = Stub(SandboxRunSupport) {
            taskRepository() >> repository
            persistence() >> new InMemoryAttemptPersistence()
            workspace() >> ({} as com.github.oinsio.gnomish.domain.engine.port.Workspace)
            pieces(_) >> new com.github.oinsio.gnomish.app.port.run.SandboxRunPieces(null, null, null, null, null, null, null)
        }
        tracker.fetchTask(_) >> heldByUs()

        when:
        def result = TakeContainerFreshClaim.claim(
                assemblyRunning(new ScriptedExecutor([completedRound()])), git, containerTakeSupport(support),
                [] as List<Segment>, new AbortHandler(tracker, FIXED_CLOCK), 3, [], CLONE_DIR, null,
                completingPipeline(), RunArguments.InteractiveMode.NONE, readyTask(), tracker, INSTANCE,
                new ClaimLossFlag())

        then: 'the clone is hardened before the branch is created (mirrors ContainerGitModeRunner)'
        1 * branches.harden(CLONE_DIR)

        then:
        1 * repository.createTask({ it.taskId() == 'PROJ-1' }, 'HEAD')

        and: 'the engine really ran the stage, and the run finished on the tracker'
        1 * tracker.finish(_, _)
        result instanceof TakeResult.Delivered
    }

    // FR9: the explicit --base is passed through to the sandbox task repository exactly as the
    // host fresh-claim path passes it to the host one.
    def "passes the explicit --base through to the sandbox task repository"() {
        given:
        def tracker = Mock(Tracker)
        def git = new TaskGit(Stub(TaskStoreGit), Stub(TaskBranchGit), Stub(TaskWorktreeGit))
        def repository = Mock(TaskRepository)
        def support = Stub(SandboxRunSupport) {
            taskRepository() >> repository
            persistence() >> new InMemoryAttemptPersistence()
            workspace() >> ({} as com.github.oinsio.gnomish.domain.engine.port.Workspace)
            pieces(_) >> new com.github.oinsio.gnomish.app.port.run.SandboxRunPieces(null, null, null, null, null, null, null)
        }
        tracker.fetchTask(_) >> heldByUs('PROJ-9')

        when:
        TakeContainerFreshClaim.claim(
                assemblyRunning(new ScriptedExecutor([completedRound()])), git, containerTakeSupport(support),
                [] as List<Segment>, new AbortHandler(tracker, FIXED_CLOCK), 3, [], CLONE_DIR, 'release/1.2',
                completingPipeline(), RunArguments.InteractiveMode.NONE, readyTask('PROJ-9'), tracker, INSTANCE,
                new ClaimLossFlag())

        then:
        1 * repository.createTask({ it.taskId() == 'PROJ-9' }, 'release/1.2')
        1 * tracker.finish(_, _)
    }
}
