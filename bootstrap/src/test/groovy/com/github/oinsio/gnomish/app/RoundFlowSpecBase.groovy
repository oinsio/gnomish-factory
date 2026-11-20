package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.CliStageExecutor
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.check.http.HttpRunContext
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.DenialCursorSource
import com.github.oinsio.gnomish.adapter.git.EnvironmentAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitObjectsTaskRepository
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.LocalBoxEnvironment
import com.github.oinsio.gnomish.adapter.git.SandboxRoundEnvironmentSource
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.git.RunRound
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.Engine
import com.github.oinsio.gnomish.domain.engine.EnginePorts
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.CheckRouting
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCheckRounds
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.EnvironmentLease
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The shared medium of the round flow specs: the domain {@link Engine} over the real round
 * adapters of a {@code gnomish run}, in either mode. Sandboxed: {@link CliStageExecutor} with the
 * fake agent, {@link SandboxRoundEnvironmentSource} over an {@link EnvironmentLease}, {@link
 * EnvironmentAttemptPersistence}, and a box that is either a {@link LocalBoxEnvironment} (real
 * clone, in-box commits, harvest; no daemon) seen through an {@link ObservedBox}, or whatever a
 * spec hands the lease. Host: the executor's host round source over a git worktree of the factory
 * clone, and {@link GitAttemptPersistence}. The model is {@code ContainerContinuationMedium}; the
 * check rounds are the ones a spec hands in — scripted, or the run's check dispatch — the rounds and
 * their commits are real. Claimless, as a {@code run} is.
 */
abstract class RoundFlowSpecBase extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    static final String TASK = FakeAgentSupport.defaultTaskContext().taskId()
    static final String BRANCH = TaskIdSanitizer.branchName(TASK)
    static final PipelineLaw LAW = PipelineLaw.ofContent(['instructions.md': 'Do the thing.'])

    @TempDir
    Path tempDir

    final GitProcessRunner runner = new GitProcessRunner()
    final CurrentRound rounds = new CurrentRound()
    Path clone

    def setup() {
        clone = initWorkingRepo(tempDir, 'clone')
        commit(clone, 'seed.txt', 'seed')
        new GitObjectsTaskRepository(objects(), new VirtualClock(Instant.parse('2026-01-01T00:00:00Z')),
                ClaimEpochSource.NONE, DenialCursorSource.NONE)
                .createTask(FakeAgentSupport.defaultTaskContext(), TaskStart.commit(clone, 'HEAD'),
                TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
    }

    /** A box over a real clone of the task branch, observed. */
    ObservedBox localBox() {
        new ObservedBox(new LocalBoxEnvironment(clone, Files.createDirectories(tempDir.resolve('box'))))
    }

    /** One stage, {@code build}, with one command check and the given attempt limit. */
    static PipelineDefinition pipeline(int attempts) {
        def stage = new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
        'instructions.md', [
            VerifyChecks.command('make check')
        ], new AutonomyLimits(attempts), AdvancementMode.AUTO)
        new PipelineDefinition('1.0.0', new AutonomyLimits(attempts), [stage])
    }

    /** Runs the pipeline to its outcome, the fake agent playing {@code scenario} in {@code box}. */
    TaskOutcome run(PipelineDefinition definition, TaskExecutionEnvironment box, String scenario,
            ScriptedCheckRounds checks) {
        def lease = new EnvironmentLease({ -> box }, BRANCH, [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), definition.stages())
        ])
        def source = new SandboxRoundEnvironmentSource(lease, runner, clone, TASK, rounds, new VirtualClock())
        // Ahead of the fixed instant the local box stamps process starts with, so wall time is positive.
        def executor = new CliStageExecutor(FakeAgentSupport.propertiesFor(scenario),
                new VirtualClock(LocalBoxEnvironment.EXEC_STAMP.plusSeconds(3600)), { e -> } as AgentProgressListener, ChildEnvAllowlist.none(), LAW, source)
        def persistence = new EnvironmentAttemptPersistence(box, runner, clone, objects(), rounds, ClaimEpochSource.NONE)
        engine(definition, CheckRouting.of(executor, checks), persistence, Files.createDirectories(tempDir.resolve('workspace')),
                source.identity())
    }

    /** A git worktree of the factory clone on the task branch: the host adapter's working copy. */
    Path worktree() {
        def path = tempDir.resolve('worktree')
        gitOutput(clone, 'worktree', 'add', path.toString(), BRANCH)
        path
    }

    /** Runs the pipeline in host mode over {@code worktree}, the agent being {@code agent}'s binary. */
    TaskOutcome runHost(PipelineDefinition definition, Path worktree, FactoryProperties agent,
            StageExecutor checks) {
        def clock = new VirtualClock()
        // The git-mode host wiring through its production owner (testing.md, "Fixtures assemble
        // through production owners"): the bundle's host-git decoration mints the tokens over the
        // host round source and snapshots a completed round outside the executor, as the run
        // assembly wires it (design D22 of migrate-verify-to-executor-contract). One cell per run,
        // shared by the round source, the snapshot and the persistence.
        def cell = new CurrentRound()
        def hostGit = TaskGitFixture.real(runner).midRoundPush()
        def rounds = hostGit.decorate(CliStageExecutor.hostRounds(clock, TestChildEnvironment.hostAllowlist()), cell)
        def executor = hostGit.snapshotting(new CliStageExecutor(agent, clock, { e -> } as AgentProgressListener,
        TestChildEnvironment.hostAllowlist(), LAW, rounds), cell)
        engine(definition, CheckRouting.of(executor, checks),
                new GitAttemptPersistence(runner, worktree, TASK, ClaimEpochSource.NONE, cell, clock), worktree, rounds.identity())
    }

    /**
     * Runs the pipeline in host mode over {@code worktree} as a git-mode {@code run} assembles it,
     * through the production owners (testing.md, "Fixtures assemble through production owners"): the
     * assembly over {@code agent} with the bundle's host-git decoration and the run's one round cell,
     * the law described by {@link ManualRunAssembly#describe}, the run's child environment from the
     * check equipment, and {@link ExecutorAdapterSelector#stageRounds}'s one port — so the stage
     * chain, the check dispatch, its registry, the run's built-ins and the round equipment (the cell
     * as the last registered commit) are the ones the run builds. The law's pins are read from the
     * clone's working tree.
     */
    TaskOutcome runHostAssembled(PipelineDefinition definition, Path worktree, FactoryProperties agent) {
        def clock = new VirtualClock()
        def run = RunRound.of(null)
        def assembly = newAssembly(agent, VirtualTimeEquipment.on(clock))
                .withHostGitPush(TaskGitFixture.real(runner).midRoundPush(), run)
        def binding = LawBinding.workingTree(clone)
        def described = assembly.describe(new RunOrder(worktree, null, definition, false), new DescribeMedium.Host(worktree),
                [], binding) as DescribedLaw
        def childEnv = assembly.checks.childEnv(assembly.sandboxProperties.envPassthrough(), definition, [])
        def rounds = ExecutorAdapterSelector.stageRounds(assembly, childEnv, LAW, described, definition,
                HttpRunContext.none(), RunLaw.open(binding).pinCheck())
        engine(definition, rounds.port(), new GitAttemptPersistence(runner, worktree, TASK, ClaimEpochSource.NONE,
                run.rounds(), clock), worktree, rounds.identity())
    }

    /** Runs the pipeline to its outcome through {@code port}, the engine's one port for stage and check rounds. */
    static TaskOutcome engine(PipelineDefinition definition, StageExecutor port, AttemptPersistence persistence,
            Path workspace, RoundIdentity identity) {
        def ports = new EnginePorts(port, new ScriptedJudgeVoter(), new RecordingEventListener(), persistence, VirtualTimeEquipment.on(new VirtualClock()), identity)
        new Engine().run(definition, FakeAgentSupport.defaultTaskContext(), TaskState.atStageStart('build'),
                new DirectoryWorkspace(workspace), ports)
    }

    String tip() {
        gitOutput(clone, 'rev-parse', 'refs/heads/' + BRANCH)
    }

    GitObjects objects() {
        GitObjects.open(clone.resolve('.git'), Files.createTempDirectory(tempDir, 'index'))
    }
}
