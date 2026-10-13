package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.CliStageExecutor
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
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
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.Engine
import com.github.oinsio.gnomish.domain.engine.EnginePorts
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
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
 * checks are scripted, the rounds and their commits are real. Claimless, as a {@code run} is.
 */
abstract class RoundFlowSpecBase extends Specification implements BareGitRepoFixture {

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
            new VerifyCheck.Command('make check')
        ], new AutonomyLimits(attempts), AdvancementMode.AUTO)
        new PipelineDefinition('1.0.0', new AutonomyLimits(attempts), [stage])
    }

    /** Runs the pipeline to its outcome, the fake agent playing {@code scenario} in {@code box}. */
    TaskOutcome run(PipelineDefinition definition, TaskExecutionEnvironment box, String scenario,
            ScriptedCommandCheckRunner checks) {
        def lease = new EnvironmentLease({ -> box }, BRANCH, [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), definition.stages())
        ])
        def source = new SandboxRoundEnvironmentSource(lease, runner, clone, TASK, rounds, new VirtualClock())
        // Ahead of the fixed instant the local box stamps process starts with, so wall time is positive.
        def executor = new CliStageExecutor(FakeAgentSupport.propertiesFor(scenario),
                new VirtualClock(LocalBoxEnvironment.EXEC_STAMP.plusSeconds(3600)), { e -> } as AgentProgressListener, ChildEnvAllowlist.none(), LAW, source)
        def persistence = new EnvironmentAttemptPersistence(box, runner, clone, objects(), rounds, ClaimEpochSource.NONE)
        engine(definition, executor, persistence, checks, Files.createDirectories(tempDir.resolve('workspace')))
    }

    /** A git worktree of the factory clone on the task branch: the host adapter's working copy. */
    Path worktree() {
        def path = tempDir.resolve('worktree')
        gitOutput(clone, 'worktree', 'add', path.toString(), BRANCH)
        path
    }

    /** Runs the pipeline in host mode over {@code worktree}, the agent being {@code agent}'s binary. */
    TaskOutcome runHost(PipelineDefinition definition, Path worktree, FactoryProperties agent,
            ScriptedCommandCheckRunner checks) {
        def clock = new VirtualClock()
        def executor = new CliStageExecutor(agent, clock, { e -> } as AgentProgressListener, TestChildEnvironment.hostAllowlist(), LAW,
        CliStageExecutor.hostRounds(clock, TestChildEnvironment.hostAllowlist()))
        engine(definition, executor, new GitAttemptPersistence(runner, worktree, TASK, ClaimEpochSource.NONE), checks, worktree)
    }

    private static TaskOutcome engine(PipelineDefinition definition, StageExecutor executor,
            AttemptPersistence persistence, ScriptedCommandCheckRunner checks, Path workspace) {
        def ports = new EnginePorts(executor, new FilesExistCheckRunner(), checks, new ScriptedExternalCheckClient(),
                new ScriptedJudgeVoter(), new RecordingEventListener(), persistence, VirtualTimeEquipment.on(new VirtualClock()))
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
