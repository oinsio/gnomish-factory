package com.github.oinsio.gnomish.app.killpoint

import static com.github.oinsio.gnomish.app.HostVerificationKill.registerRef
import static com.github.oinsio.gnomish.app.HostVerificationKill.stateAt
import static com.github.oinsio.gnomish.app.killpoint.PendingVerificationKill.commitOf

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.agent.ResumeVerificationStageExecutor
import com.github.oinsio.gnomish.adapter.git.BestEffortClosingPush
import com.github.oinsio.gnomish.adapter.git.EnvironmentAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.EnvironmentRoundSnapshot
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.LocalBoxEnvironment
import com.github.oinsio.gnomish.adapter.git.PushBestEffortAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.SandboxRoundEnvironmentSource
import com.github.oinsio.gnomish.adapter.git.SnapshotTipCheck
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.BuiltInEntriesFixture
import com.github.oinsio.gnomish.app.ClaudeStageFixture
import com.github.oinsio.gnomish.app.HandAssembledCheckDispatch
import com.github.oinsio.gnomish.app.KillPointTakeRoutes
import com.github.oinsio.gnomish.app.TaskGitFixture
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.git.LocalTaskBranch
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.engine.*
import com.github.oinsio.gnomish.domain.engine.fake.*
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.EnvironmentLease
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path

/**
 * The container medium of the pending-poll kill windows on the clone that wrote them, without a
 * daemon (task 4.3b of migrate-verify-to-executor-contract; design D21, D22): every box is a {@link
 * LocalBoxEnvironment} — a real clone, real in-box commits, a real harvest — over the factory clone
 * of a container take world.
 *
 * <p>The dying instance's writes are the production ones: the round's snapshot by {@link
 * EnvironmentRoundSnapshot} over the run's cell, then the persistence's pending write — the in-box
 * commit through a private index, its harvest and admission — pushed best-effort, as {@code
 * ContainerRunSupport#persistence} assembles it.
 *
 * <p>The pickup is the container resume one layer down, the way {@link ContainerRoundWriter} drives
 * a round: the resume-start reconciliation, the one recogniser ({@link SnapshotTipCheck}), the state
 * read at the register through the live path's parse, a box seeded at the branch, and the domain
 * {@link Engine} over the resume decorator, the run's check dispatch with the operator's {@code
 * loopback} scope, and the production persistence. The stage executor is counted.
 */
final class ContainerPendingKill {

    static final String GNOME_FILE = 'gnome.txt'

    private static final PipelineLaw LAW = PipelineLaw.ofContent(['instructions.md': 'build it\n'])

    private final TakeKillPointWorld world
    private final Path root
    private final GitProcessRunner runner = new GitProcessRunner()

    /** The dying instance's box, live from its round's snapshot to the kill. */
    LocalBoxEnvironment dyingBox

    /** The stage-executor rounds every pickup of this world ran. */
    int agentRounds

    /** When set, the law's program checks, bound through a fresh box per request (task 5.3). */
    ContainerProgramChecks programs

    ContainerPendingKill(TakeKillPointWorld world, Path root) {
        this.world = world
        this.root = root
    }

    /**
     * The dying instance's round {@code round} of {@code stage}: opened over the branch tip in a box
     * seeded there, the gnome's file written, the snapshot harvested and registered — then the kill.
     *
     * @return the run's cell, holding the round closed at its snapshot
     */
    CurrentRound killAfterSnapshot(String stage, int round) {
        def cell = new CurrentRound()
        cell.open(RoundToken.of(commitOf(world.repoDir, "refs/heads/${branch()}")))
        dyingBox = new LocalBoxEnvironment(world.repoDir, Files.createTempDirectory(root, 'box'))
        dyingBox.materialize(branch(), null)
        Files.writeString(dyingBox.workingCopy.resolve(GNOME_FILE), 'the gnome\'s work')
        new EnvironmentRoundSnapshot(dyingBox, runner, world.repoDir, cell).snapshot(world.taskId, stage, round, RoundClose.VERIFIABLE)
        cell
    }

    /**
     * The dying instance's pending write of {@code entries} ({@link PendingVerificationKill#records})
     * over the state its snapshot registered, byte for byte (D21 item 12), from its own box.
     */
    List<PendingPollRecord> landEntries(CurrentRound cell, int round, List<List<Object>> entries) {
        def records = PendingVerificationKill.records(world, cell, round, entries)
        persistence(dyingBox, cell).persistPending(world.taskId,
                stateAt(world.repoDir, cell.closed().registered()).withPendingPolls(records))
        records
    }

    /** The dying instance closes its round: {@code state} committed in its box over the round's last registered commit. */
    void close(CurrentRound cell, TaskState state, int round, String stage) {
        persistence(dyingBox, cell).persist(world.taskId, state, new ToolTrace(new AttemptKey(world.taskId, stage, round), []))
    }

    /** One pickup of the task on this clone, after the kill; its outcome is the engine's. */
    TaskOutcome pickUp() {
        def branches = TaskGitFixture.real().branches()
        branches.requireRefFormat(world.repoDir)
        assert branches.ensureLocalTaskBranch(world.repoDir, world.taskId) instanceof LocalTaskBranch
        branches.reconcileRemote(world.repoDir, world.taskId, 'resume-start')
        def found = new SnapshotTipCheck(runner, world.repoDir).inspect(world.taskId)
        def recorded = stateAt(world.repoDir, commitOf(world.repoDir, registerRef(world.taskId)))
        def cell = new CurrentRound()
        def box = new LocalBoxEnvironment(world.repoDir, Files.createTempDirectory(root, 'box'))
        def lease = new EnvironmentLease({
            -> box
        }, branch(), KillPointTakeRoutes.segments(world.definition))
        // The box made live for the recorded stage before the engine, as ContainerResumePreparation
        // reattaches it, so the same-box close of a resumed round has somewhere to commit.
        if (recorded.position() instanceof Position.AtStage) {
            lease.environmentFor((recorded.position() as Position.AtStage).name())
        }
        def source = new SandboxRoundEnvironmentSource(lease, runner, world.repoDir, world.taskId, cell, new VirtualClock())
        def agent = ClaudeStageFixture.executor(FakeAgentSupport.propertiesFor('plain-round'), new VirtualClock(LocalBoxEnvironment.EXEC_STAMP.plusSeconds(3600)), ChildEnvAllowlist.none(), LAW, source)
        def counted = { StageExecutor.Request request ->
            agentRounds++
            agent.execute(request)
        } as StageExecutor
        // The round's clock starts at the entries' `since`, so a recorded deadline is the check's own.
        def clock = new VirtualClock()
        def resume = new ResumeVerificationStageExecutor(counted, cell, found.orElse(null))
        def port = programs != null ? programs.port(resume, world, cell)
                : HandAssembledCheckDispatch.routed(resume, world.definition, world.repoDir, BuiltInEntriesFixture.of(clock, [:], ['loopback']))
        // A program's fresh box reads the attempt commit off the run's cell, as the container run's workspace does.
        def workspace = programs != null ? new RecordedAttemptCommitWorkspace(cell)
                : new DirectoryWorkspace(Files.createTempDirectory(root, 'workspace'))
        def ports = new EnginePorts(port, new RecordingEventListener(), persistence(box, cell), VirtualTimeEquipment.on(clock),
                source.identity())
        new Engine().run(world.definition, new TaskContext(world.taskId, UntrustedText.tracker('title'),
                UntrustedText.tracker('body'), []), recorded, workspace, ports)
    }

    /** The persistence a round in {@code box} writes through, behind the closing push. */
    private AttemptPersistence persistence(TaskExecutionEnvironment box, CurrentRound cell) {
        def objects = GitObjects.open(world.repoDir.resolve('.git'), Files.createTempDirectory(root, 'index'))
        new PushBestEffortAttemptPersistence(new EnvironmentAttemptPersistence(box, runner, world.repoDir, objects, cell, world.epochs),
                new BestEffortClosingPush(runner), world.repoDir)
    }

    private String branch() {
        TaskIdSanitizer.branchName(world.taskId)
    }
}
