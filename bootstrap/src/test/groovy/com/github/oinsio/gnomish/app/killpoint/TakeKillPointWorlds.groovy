package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.DenialCursorSource
import com.github.oinsio.gnomish.adapter.git.GitAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitObjectsTaskRepository
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.KillPointTakeRoutes
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.TaskGitFixture
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.*
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.sandbox.environment.ScriptedSandboxDocker
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Builds the two media a {@code take} pickup can find frozen, each with its real round writer and
 * its real take routes (NFR-R1, design D8 of make-checkpoint-gate-durable): a registered factory
 * clone with a converged {@code origin}, a task created and claimed by this instance on an
 * in-memory tracker, and one {@link TaskGit} bundle whose tenure record every writer stamps from.
 * Each world also carries the second instance's own bundle and the take entries over it, so a
 * pickup can take the task over the way production does (design D3 of
 * fix-terminal-receipt-convergence).
 *
 * <p>The round writers differ by medium, as production's do. The host lands the round through
 * {@link GitAttemptPersistence} in the task's worktree, as {@code ReclaimKillPoints} does; the
 * container round is the whole production round, written by {@link ContainerRoundWriter}.
 */
trait TakeKillPointWorlds implements BareGitRepoFixture {

    static final String TAKE_TASK_ID = 'PROJ-1'

    /** The host medium: worktrees, {@link GitTaskRepository}, the host take routes. */
    TakeKillPointWorld takeHostWorld(Path root, PipelineDefinition definition) {
        Path clone = takeClone(root)
        def registered = RegisteredCloneFixture.registered(root.resolve('home'), clone)
        def git = TaskGitFixture.real()
        Path argv = root.resolve('plain-round.log')
        def agent = FakeAgentSupport.propertiesCapturingArgv('plain-round', argv)
        // The second instance's git is the recording stand-in, so a row can bound what its pickup
        // spends on reads of the worktree's own HEAD (NFR-P1 of fix-envelope-medium).
        Path gitStandIn = recordingGit(root.resolve('git-invocations.log'))
        def world = new TakeKillPointWorld(definition: definition, repoDir: clone, epochs: git.epochs(),
        worktree: registered.worktrees().resolve(TAKE_TASK_ID), gitStandIn: gitStandIn,
        secondGit: TaskGitFixture.real(new GitProcessRunner(gitStandIn.toString())),
        agentRounds: {
            -> Files.exists(argv) ? FakeAgentSupport.capturedInvocations(argv).size() : 0
        })
        world.store = RegisteringStore.of(
                new GitTaskRepository(new GitProcessRunner(), registered, git.epochs(), new VirtualClock()), new GitProcessRunner(), registered.clonePath())
        world.roundWriter = { TaskState expected ->
            new GitAttemptPersistence(new GitProcessRunner(), world.worktree, TAKE_TASK_ID, git.epochs())
            .persist(TAKE_TASK_ID, expected, trace(expected))
        }
        seedClaim(world)
        world.routes = new KillPointTakeRoutes().host(registered, git, world.tracker, definition, agent)
        world.secondRoutes = new KillPointTakeRoutes().host(registered, world.secondGit, world.tracker, definition, agent)
        createTask(world)
    }

    /** The container medium: the clone's objects, {@link GitObjectsTaskRepository}, the container routes. */
    TakeKillPointWorld takeContainerWorld(Path root, PipelineDefinition definition) {
        takeContainerWorld(root, definition, DenialCursorSource.NONE)
    }

    /**
     * The container medium whose lifecycle writer asks {@code cursors} for the denial read position
     * an escalation commits (FR5 of fix-denial-attribution-durability).
     */
    TakeKillPointWorld takeContainerWorld(Path root, PipelineDefinition definition, DenialCursorSource cursors) {
        Path clone = takeClone(root)
        def registered = RegisteredCloneFixture.registered(root.resolve('home'), clone)
        def git = TaskGitFixture.real()
        def docker = new ScriptedSandboxDocker()
        def objects = GitObjects.open(clone.resolve('.git'), Files.createDirectories(root.resolve('index')))
        def world = new TakeKillPointWorld(definition: definition, repoDir: clone, epochs: git.epochs(),
        secondGit: TaskGitFixture.real(),
        agentRounds: {
            -> docker.starts.count {
                it.contains('stream-json')
            } as Integer
        })
        world.store = RegisteringStore.of(
                new GitObjectsTaskRepository(objects, new VirtualClock(Instant.parse('2026-01-01T00:00:00Z')), git.epochs(), cursors),
                new GitProcessRunner(), clone)
        world.roundWriter = { TaskState expected ->
            new ContainerRoundWriter(world, root, objects).write(expected)
        }
        seedClaim(world)
        world.routes = new KillPointTakeRoutes().container(registered, git, world.tracker, definition, docker)
        world.secondRoutes = new KillPointTakeRoutes().container(registered, world.secondGit, world.tracker, definition, docker)
        createTask(world)
    }

    private Path takeClone(Path root) {
        Path clone = initWorkingRepo(root, 'my-project')
        Files.createDirectories(clone.resolve('.gnomish'))
        Files.writeString(clone.resolve('.gnomish/instructions.md'), 'build it\n')
        commitAll(clone, 'init')
        addConvergedOrigin(clone, root)
        clone
    }

    /** The claim this instance holds, its epoch recorded before the first write (as in {@code KillPointWorlds}). */
    private void seedClaim(TakeKillPointWorld world) {
        world.tracker = new InMemoryTracker()
        world.trackerHarness = new InMemoryTrackerHarness(world.tracker)
        world.instanceId = new InstanceId('gnomish-factory', 'kp0003')
        world.ref = new TaskRef(TAKE_TASK_ID)
        world.taskId = TAKE_TASK_ID
        world.trackerHarness.seedWorkingWithClaim(world.tracker, world.ref, world.instanceId.value())
        world.epochs.issued(TAKE_TASK_ID, world.tracker.listOpen().find {
            it.ref() == world.ref
        }.facts().claim().liveVersion().epoch())
    }

    private TakeKillPointWorld createTask(TakeKillPointWorld world) {
        String base = currentBranch(world.repoDir)
        world.store.createTask(new TaskContext(TAKE_TASK_ID, UntrustedText.tracker('title'), UntrustedText.tracker('body'), []),
        TaskStart.commit(world.repoDir, base), TaskStart.pin(base, BaseRule.EXPLICIT_ARGUMENT),
        TaskState.atStageStart(world.definition.stages().first().name()))
        world
    }

    private static ToolTrace trace(TaskState state) {
        def round = state.attempts().last()
        new ToolTrace(new AttemptKey(TAKE_TASK_ID, 'build', round.round()),
                [
                    new ToolCall(0, 'bash', Instant.parse('2026-10-08T09:00:00Z'), Duration.ofMillis(50))
                ])
    }
}
