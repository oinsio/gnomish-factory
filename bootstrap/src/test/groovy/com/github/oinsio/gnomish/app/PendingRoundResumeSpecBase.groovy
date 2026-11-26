package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.check.http.HttpRunContext
import com.github.oinsio.gnomish.adapter.git.*
import com.github.oinsio.gnomish.app.PendingRoundKills.Kill
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.git.PendingVerification
import com.github.oinsio.gnomish.app.port.git.RunRound
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.engine.*
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.sandbox.*
import com.github.oinsio.gnomish.sandbox.environment.EnvironmentLease
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path

/**
 * The fixture of the specs in which a pending {@code transform} round outlives the instance that ran it
 * (FR11, NFR-R3, NFR-R4, NFR-C2 of extract-agent-round-runner; design D11 as amended 2026-11-25, its
 * kill windows K1–K5; crash-consistency items 8, 10, 14): an instance runs the engine from the state at
 * the register, through the production owners {@link PendingRoundFlowSpecBase} names, over a real bare
 * origin, and dies where its {@link Kill} says; a second instance reads the register through the
 * production recogniser ({@link #recognise}), restores the round cell at its assembly and runs on. The
 * kills are {@link PendingRoundKills}'.
 */
abstract class PendingRoundResumeSpecBase extends PendingRoundFlowSpecBase {

    final TaskGit git = TaskGitFixture.real(runner)

    /** What a second instance's pickup reads at the register: on the host after its leftovers are handled. */
    protected PendingVerification recognise(String mode) {
        mode == 'host'
                ? NoSalvageOverVerification.onHost(git.worktrees(), tempDir.resolve('worktree'), TASK, false)
                : new SnapshotTipCheck(runner, clone).inspect(TASK).orElse(null)
    }

    /** One instance in {@code mode}; a box instance runs in a box of its own named {@code box}. */
    protected TaskOutcome instance(String mode, RecordingEventListener listener, PendingVerification pending,
            String box, Kill kill = new Kill()) {
        mode == 'host' ? hostRun(listener, pending, kill) : boxRun(listener, pending, box, kill)
    }

    /** One instance's host run as a git-mode {@code run} assembles it, over the task's one worktree. */
    private TaskOutcome hostRun(RecordingEventListener listener, PendingVerification pending, Kill kill) {
        def workingCopy = tempDir.resolve('worktree')
        if (!Files.exists(workingCopy)) {
            worktree()
        }
        def taskGit = kill.git == null ? git : TaskGitFixture.real(kill.git)
        def run = RunRound.of(pending)
        def assembly = newAssembly(FakeAgentSupport.propertiesFor('plain-round'), VirtualTimeEquipment.on(new VirtualClock()))
                .withHostGitPush(taskGit.midRoundPush(), run)
        def persistence = taskGit.store().attemptPersistence(workingCopy, TASK, run.rounds())
        resumed(assembly, workingCopy, kill.persistence(persistence), new DirectoryWorkspace(workingCopy), listener, kill)
    }

    /** One instance's box run as {@code ContainerRunSupport} assembles it, in a box of its own seeded at the register. */
    private TaskOutcome boxRun(RecordingEventListener listener, PendingVerification pending, String boxName, Kill kill) {
        def git = kill.git ?: runner
        def clock = new VirtualClock(LocalBoxEnvironment.EXEC_STAMP.plusSeconds(3600))
        def box = new LocalBoxEnvironment(clone, Files.createDirectories(tempDir.resolve(boxName)))
        def cell = new CurrentRound()
        def lease = new EnvironmentLease({ -> box }, BRANCH, [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), pipeline().stages())
        ])
        if (pending != null) {
            // As ContainerRunSupport.reattachFor makes the box live before a resume over an interrupted
            // verification: the round source opens no round on the way to re-verify it.
            lease.environmentFor('build')
        }
        def pieces = new SandboxRunPieces(new SandboxRoundEnvironmentSource(lease, git, clone, TASK, cell, clock),
                null, null, objects(), new RemoteAttemptDelivery(git, clone, TASK), cell, pending)
        def assembly = newAssembly(FakeAgentSupport.propertiesFor('plain-round'), VirtualTimeEquipment.on(clock)).withSandbox(pieces)
        def persistence = new PushBestEffortAttemptPersistence(
                new EnvironmentAttemptPersistence(box, git, clone, objects(), cell, this.git.epochs()),
                new BestEffortClosingPush(git), clone)
        resumed(assembly, clone, kill.persistence(persistence), new RecordedAttemptCommitWorkspace(cell), listener, kill)
    }

    /** The engine over the run's assembled port, from the state at the register, as a resume reads it. */
    private TaskOutcome resumed(ManualRunAssembly assembly, Path lawRoot, AttemptPersistence persistence,
            Workspace workspace, RecordingEventListener listener, Kill kill) {
        def definition = pipeline()
        def binding = LawBinding.workingTree(clone)
        def described = assembly.describe(new RunOrder(lawRoot, null, definition, false), new DescribeMedium.Host(lawRoot),
                [], binding) as DescribedLaw
        def childEnv = assembly.checks.childEnv(assembly.sandboxProperties.envPassthrough(), definition, [])
        def stageRounds = ExecutorAdapterSelector.stageRounds(assembly, childEnv, LAW, described, definition,
                HttpRunContext.none(), RunLaw.open(binding).pinCheck())
        def ports = new EnginePorts(kill.port(stageRounds.port()), listener, persistence, assembly.time,
                assembly.sandbox == null ? AttemptDelivery.assumedDelivered() : assembly.sandbox.attemptDelivery(),
                described.executors(), stageRounds.identity())
        new Engine().run(definition, FakeAgentSupport.defaultTaskContext(), stateAt(REGISTER), workspace, ports)
    }

    /**
     * A second recovery pass over the closed round changes nothing (crash-consistency item 8): the
     * recogniser names no interrupted verification, the executor is asked nothing, no round or check
     * finishes (so no ledger line), and the branch, its register and origin stay where they were.
     */
    protected void secondPassIsNoOp(String mode) {
        def before = [
            tip(),
            gitOutput(clone, 'rev-parse', REGISTER),
            originTip(),
            requests()
        ]
        def pending = recognise(mode)
        def listener = new RecordingEventListener()
        def outcome = instance(mode, listener, pending, 'box-again')
        assert pending == null
        assert outcome instanceof TaskOutcome.Completed
        assert [
            tip(),
            gitOutput(clone, 'rev-parse', REGISTER),
            originTip(),
            requests()
        ] == before
        assert listener.events.findAll {
            it instanceof EngineEvent.ExecutionFinished || it instanceof EngineEvent.CheckFinished || it instanceof EngineEvent.PendingPolled
        }.isEmpty()
    }

    protected List<String> requests() {
        StandIn.beside(link, 'heads').toFile().readLines()*.split(' ')*.take(2)*.join(' ')
    }

    protected static EngineEvent.ExecutionFinished finished(RecordingEventListener listener) {
        listener.events.find {
            it instanceof EngineEvent.ExecutionFinished
        } as EngineEvent.ExecutionFinished
    }

    protected String originTip() {
        gitOutput(clone, 'ls-remote', 'origin', 'refs/heads/' + BRANCH).split('\t')[0]
    }
}
