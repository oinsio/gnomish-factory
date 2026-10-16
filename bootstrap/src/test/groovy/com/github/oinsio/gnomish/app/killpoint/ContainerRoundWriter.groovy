package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.adapter.agent.CliStageExecutor
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.git.BestEffortClosingPush
import com.github.oinsio.gnomish.adapter.git.EnvironmentAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.LocalBoxEnvironment
import com.github.oinsio.gnomish.adapter.git.PushBestEffortAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.SandboxRoundEnvironmentSource
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.KillPointTakeRoutes
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.*
import com.github.oinsio.gnomish.domain.engine.fake.*
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.environment.EnvironmentLease
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path

/**
 * The container medium's round writer of a {@link TakeKillPointWorld}: the whole production round,
 * driven one layer down the way {@code ContainerContinuationMedium} drives it — the domain {@link
 * Engine} over {@link CliStageExecutor}, {@link SandboxRoundEnvironmentSource} and {@link
 * EnvironmentAttemptPersistence} in a {@link LocalBoxEnvironment} — because a box over the scripted
 * docker never closes a round; the engine's outcome is dropped, which is the kill before the park
 * commit (design D8 of make-checkpoint-gate-durable).
 */
final class ContainerRoundWriter {

    private static final PipelineLaw TAKE_LAW = PipelineLaw.ofContent(['instructions.md': 'build it\n'])

    private final TakeKillPointWorld world
    private final Path root
    private final GitObjects objects

    /**
     * @param world the world whose task the round is written for
     * @param root the world's directory, where the box and the workspace are made
     * @param objects the clone's objects, which the round's persistence writes through
     */
    ContainerRoundWriter(TakeKillPointWorld world, Path root, GitObjects objects) {
        this.world = world
        this.root = root
        this.objects = objects
    }

    /**
     * One production container round of the world's first stage, the fake agent playing the gnome
     * {@code expected} describes — asking when its last round carries a stop, passing otherwise —
     * and the tip it leaves checked against {@code expected}'s position.
     */
    void write(TaskState expected) {
        boolean asks = expected.attempts().last().stop() instanceof Stop.DecisionNeeded
        def rounds = new CurrentRound()
        def box = new LocalBoxEnvironment(world.repoDir, Files.createTempDirectory(root, 'box'))
        def lease = new EnvironmentLease({
            -> box
        }, TaskIdSanitizer.branchName(world.taskId),
        KillPointTakeRoutes.segments(world.definition))
        def source = new SandboxRoundEnvironmentSource(lease, new GitProcessRunner(), world.repoDir, world.taskId, rounds,
                new VirtualClock())
        def executor = new CliStageExecutor(FakeAgentSupport.propertiesFor(asks ? 'decision-needed' : 'plain-round'),
                new VirtualClock(LocalBoxEnvironment.EXEC_STAMP.plusSeconds(3600)), { e -> } as AgentProgressListener, ChildEnvAllowlist.none(), TAKE_LAW, source)
        // Behind the closing push, as ContainerRunSupport#persistence assembles it: the state commit
        // becomes the factory register (design D13 of tighten-spec-contract).
        def persistence = new PushBestEffortAttemptPersistence(
                new EnvironmentAttemptPersistence(box, new GitProcessRunner(), world.repoDir, objects, rounds, world.epochs),
                new BestEffortClosingPush(new GitProcessRunner()), world.repoDir)
        def clock = new VirtualClock()
        def ports = new EnginePorts(executor, new FilesExistCheckRunner(), new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), new ScriptedJudgeVoter(), new RecordingEventListener(), persistence,
                VirtualTimeEquipment.on(clock))
        new Engine().run(world.definition, context(),
                TaskState.atStageStart(world.definition.stages().first().name()),
                new DirectoryWorkspace(Files.createTempDirectory(root, 'workspace')), ports)
        assert world.tipState().position() == expected.position(): 'the container round left another position'
        assert world.tipState().attempts().last().stop().class == expected.attempts().last().stop().class
    }

    private TaskContext context() {
        new TaskContext(world.taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])
    }
}
