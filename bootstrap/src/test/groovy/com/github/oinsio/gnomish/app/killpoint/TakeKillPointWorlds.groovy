package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.adapter.agent.CliStageExecutor
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.DenialCursorSource
import com.github.oinsio.gnomish.adapter.git.EnvironmentAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitObjectsTaskRepository
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository
import com.github.oinsio.gnomish.adapter.git.LocalBoxEnvironment
import com.github.oinsio.gnomish.adapter.git.SandboxRoundEnvironmentSource
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.KillPointTakeRoutes
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.TaskGitFixture
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.*
import com.github.oinsio.gnomish.domain.engine.fake.*
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.sandbox.environment.EnvironmentLease
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
 *
 * <p>The round writers differ by medium, as production's do. The host lands the round through
 * {@link GitAttemptPersistence} in the task's worktree, as {@code ReclaimKillPoints} does. The
 * container round is the whole production round, driven one layer down the way {@code
 * ContainerContinuationMedium} drives it — the domain {@link Engine} over {@link CliStageExecutor},
 * {@link SandboxRoundEnvironmentSource} and {@link EnvironmentAttemptPersistence} in a {@link
 * LocalBoxEnvironment} — because a box over the scripted docker never closes a round; the engine's
 * outcome is dropped, which is the kill before the park commit.
 */
trait TakeKillPointWorlds implements BareGitRepoFixture {

    static final String TAKE_TASK_ID = 'PROJ-1'

    private static final PipelineLaw TAKE_LAW = PipelineLaw.ofContent(['instructions.md': 'build it\n'])

    /** The host medium: worktrees, {@link GitTaskRepository}, the host take routes. */
    TakeKillPointWorld takeHostWorld(Path root, PipelineDefinition definition) {
        Path clone = takeClone(root)
        def registered = RegisteredCloneFixture.registered(root.resolve('home'), clone)
        def git = TaskGitFixture.real()
        Path argv = root.resolve('agent-argv.log')
        def agent = FakeAgentSupport.propertiesCapturingArgv('plain-round', 'judge-model', 'judge-verdict-pass', argv)
        def world = new TakeKillPointWorld(definition: definition, repoDir: clone, epochs: git.epochs(),
        worktree: registered.worktrees().resolve(TAKE_TASK_ID),
        agentRounds: {
            -> Files.exists(argv) ? FakeAgentSupport.capturedInvocations(argv).size() : 0
        })
        world.store = new GitTaskRepository(new GitProcessRunner(), registered, git.epochs())
        world.roundWriter = { TaskState expected ->
            new GitAttemptPersistence(new GitProcessRunner(), world.worktree, TAKE_TASK_ID, git.epochs())
            .persist(TAKE_TASK_ID, expected, trace(expected))
        }
        seedClaim(world)
        world.routes = new KillPointTakeRoutes().host(registered, git, world.tracker, definition, agent)
        createTask(world)
    }

    /** The container medium: the clone's objects, {@link GitObjectsTaskRepository}, the container routes. */
    TakeKillPointWorld takeContainerWorld(Path root, PipelineDefinition definition) {
        Path clone = takeClone(root)
        def registered = RegisteredCloneFixture.registered(root.resolve('home'), clone)
        def git = TaskGitFixture.real()
        def docker = new ScriptedSandboxDocker()
        def objects = GitObjects.open(clone.resolve('.git'), Files.createDirectories(root.resolve('index')))
        def world = new TakeKillPointWorld(definition: definition, repoDir: clone, epochs: git.epochs(),
        agentRounds: {
            -> docker.starts.count {
                it.contains('stream-json')
            } as Integer
        })
        world.store = new GitObjectsTaskRepository(objects, git.epochs(), DenialCursorSource.NONE)
        world.roundWriter = { TaskState expected ->
            containerRound(world, root, objects, expected)
        }
        seedClaim(world)
        world.routes = new KillPointTakeRoutes().container(registered, git, world.tracker, definition, docker)
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

    /**
     * One production container round of the world's first stage, the fake agent playing the gnome
     * {@code expected} describes — asking when its last round carries a stop, passing otherwise —
     * and the tip it leaves checked against {@code expected}'s position.
     */
    private void containerRound(TakeKillPointWorld world, Path root, GitObjects objects, TaskState expected) {
        boolean asks = expected.attempts().last().stop() instanceof Stop.DecisionNeeded
        def rounds = new CurrentRound()
        def box = new LocalBoxEnvironment(world.repoDir, Files.createTempDirectory(root, 'box'))
        def lease = new EnvironmentLease({
            -> box
        }, TaskIdSanitizer.branchName(TAKE_TASK_ID),
        KillPointTakeRoutes.segments(world.definition))
        def source = new SandboxRoundEnvironmentSource(lease, new GitProcessRunner(), world.repoDir, TAKE_TASK_ID, rounds,
                new VirtualClock())
        def executor = new CliStageExecutor(FakeAgentSupport.propertiesFor(asks ? 'decision-needed' : 'plain-round'),
                new VirtualClock(Instant.now().plusSeconds(3600)), { e -> } as AgentProgressListener, TAKE_LAW, source)
        def persistence = new EnvironmentAttemptPersistence(box, new GitProcessRunner(), world.repoDir, objects,
                TAKE_TASK_ID, rounds, world.epochs)
        def clock = new VirtualClock()
        def ports = new EnginePorts(executor, new FilesExistCheckRunner(), new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), new ScriptedJudgeVoter(), new RecordingEventListener(), persistence,
                clock, new VirtualSleeper(clock))
        new Engine().run(world.definition, context(),
                TaskState.atStageStart(world.definition.stages().first().name()),
                new DirectoryWorkspace(Files.createTempDirectory(root, 'workspace')), ports)
        assert world.tipState().position() == expected.position(): 'the container round left another position'
        assert world.tipState().attempts().last().stop().class == expected.attempts().last().stop().class
    }

    private static TaskContext context() {
        new TaskContext(TAKE_TASK_ID, UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])
    }
}
