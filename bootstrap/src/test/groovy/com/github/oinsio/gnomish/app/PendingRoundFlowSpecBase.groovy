package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.check.http.HttpRunContext
import com.github.oinsio.gnomish.adapter.git.*
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.app.port.git.RunRound
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
import com.github.oinsio.gnomish.domain.pipeline.*
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.sandbox.*
import com.github.oinsio.gnomish.sandbox.environment.EnvironmentLease
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path

/**
 * The fixture of the pending-round flow specs (FR10, FR12, NFR-R4 of extract-agent-round-runner; design
 * D11 as amended 2026-11-25, tasks 6.4a, 6.6): the {@link Engine} over the stage chain a run assembles
 * through its production owners ({@link ExecutorAdapterSelector#stageRounds}) — on the host the host-git
 * decoration and {@link GitAttemptPersistence}; in a box the sandboxed round source over a {@link
 * LocalBoxEnvironment} and the container persistence behind its closing push — with a real bare origin.
 * The stage runs the {@code transform-scripted} stand-in, which declares {@code poll} and answers each
 * request with the next line of its script: by default {@code start} answers {@code pending}, two polls
 * {@code pending} with the recorded key, the third {@code completed}; the round closes and its one
 * {@code files-exist} check runs. Every pending write records the branch tip, its register and origin's tip.
 */
abstract class PendingRoundFlowSpecBase extends RoundFlowSpecBase {

    static final String KEY = 'job-7'
    static final String REGISTER = 'refs/gnomish-factory/' + TASK

    Path link
    String created
    List<Map<String, Object>> writes = []

    def setup() {
        addConvergedOrigin(clone, tempDir)
        assert runner.run(clone, 'push', '--atomic', 'origin', "refs/heads/${BRANCH}:refs/heads/${BRANCH}",
        "${REGISTER}:${REGISTER}").exitCode() == 0
        created = tip()
        link = StandIn.recording(Files.createDirectories(tempDir.resolve('bin')), 'transform-scripted')
        script([pendingAnswer()] * 3 << completedAnswer())
    }

    /** Replaces the stand-in's answers, one line per request. */
    protected void script(List<String> answers) {
        Files.writeString(StandIn.beside(link, 'script'), answers.join('\n') + '\n')
    }

    static String completedAnswer() {
        StandIn.data('executor-result#transform').trim()
    }


    /** The host run as a git-mode {@code run} assembles it (cf. {@link RoundFlowSpecBase#runHostAssembled}). */
    protected TaskOutcome runHost() {
        def worktree = worktree()
        def clock = new VirtualClock()
        def hostRun = RunRound.of(null)
        // One bundle, as GitModeRunner takes it: the decoration and the run's persistence come from it.
        def git = TaskGitFixture.real(runner)
        def assembly = newAssembly(FakeAgentSupport.propertiesFor('plain-round'), VirtualTimeEquipment.on(clock))
                .withHostGitPush(git.midRoundPush(), hostRun)
        def persistence = git.store().attemptPersistence(worktree, TASK, hostRun.rounds())
        flow(assembly, worktree, persistence, new DirectoryWorkspace(worktree))
    }

    /** The box run as {@code ContainerRunSupport} assembles its pieces and persistence, over a daemon-free box. */
    protected TaskOutcome runBox() {
        // Ahead of the fixed instant the local box stamps process starts with, so wall time is positive.
        def clock = new VirtualClock(LocalBoxEnvironment.EXEC_STAMP.plusSeconds(3600))
        def box = new LocalBoxEnvironment(clone, Files.createDirectories(tempDir.resolve('box')))
        // The tenure as ContainerSupports reads it: the bundle's own book, which a run never fills.
        def git = TaskGitFixture.real(runner)
        def lease = new EnvironmentLease({ -> box }, BRANCH, [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), pipeline().stages())
        ])
        def pieces = new SandboxRunPieces(new SandboxRoundEnvironmentSource(lease, runner, clone, TASK, rounds, clock),
                null, null, objects(), new RemoteAttemptDelivery(runner, clone, TASK), rounds, null)
        def assembly = newAssembly(FakeAgentSupport.propertiesFor('plain-round'), VirtualTimeEquipment.on(clock)).withSandbox(pieces)
        def persistence = new PushBestEffortAttemptPersistence(
                new EnvironmentAttemptPersistence(box, runner, clone, objects(), rounds, git.epochs()),
                new BestEffortClosingPush(runner), clone)
        flow(assembly, clone, persistence, new RecordedAttemptCommitWorkspace(rounds))
    }

    protected TaskOutcome flow(ManualRunAssembly assembly, Path lawRoot, AttemptPersistence persistence, Workspace workspace) {
        def definition = pipeline()
        def binding = LawBinding.workingTree(clone)
        def described = assembly.describe(new RunOrder(lawRoot, null, definition, false), new DescribeMedium.Host(lawRoot),
                [], binding) as DescribedLaw
        def childEnv = assembly.checks.childEnv(assembly.sandboxProperties.envPassthrough(), definition, [])
        def stageRounds = ExecutorAdapterSelector.stageRounds(assembly, childEnv, LAW, described, definition,
                HttpRunContext.none(), RunLaw.open(binding).pinCheck())
        // As RunAssembler builds the ports: the run's time, its delivery, and what the describe preflight obtained.
        def ports = new EnginePorts(stageRounds.port(), new RecordingEventListener(), observed(persistence), assembly.time,
                assembly.sandbox == null ? AttemptDelivery.assumedDelivered() : assembly.sandbox.attemptDelivery(),
                described.executors(), stageRounds.identity())
        new Engine().run(definition, FakeAgentSupport.defaultTaskContext(), TaskState.atStageStart('build'), workspace, ports)
    }

    /** One stage {@code build}, run by the law's {@code transform} program and verified by one {@code files-exist} check. */
    protected PipelineDefinition pipeline() {
        def checks = [
            VerifyChecks.builtin('files', [files: ['seed.txt']])
        ]
        def stage = new StageDefinition('build', 'purpose', [], [], new StageDefinition.Executor(ExecutorType.PROGRAM, '', [:],
        Sandbox.none(), 'acme'), 'instructions.md', checks, new AutonomyLimits(1), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(1), [stage], null, [
            new ExecutorDeclaration('acme', ExecutorDeclaration.Type.PROGRAM, [link.toString()], [:], [], [])
        ])
    }

    /** {@code persistence}, recording the branch tip and the register after each write and before the round's close. */
    protected AttemptPersistence observed(AttemptPersistence persistence) {
        [
            persistPending: { String taskId, TaskState state ->
                persistence.persistPending(taskId, state)
                writes << [op: 'pending', tip: tip(), register: gitOutput(clone, 'rev-parse', REGISTER),
                    origin: gitOutput(clone, 'ls-remote', 'origin', 'refs/heads/' + BRANCH).split('\t')[0]]
            },
            persist: { String taskId, TaskState state, ToolTrace trace ->
                writes << [op: 'round', tip: tip(), register: gitOutput(clone, 'rev-parse', REGISTER)]
                persistence.persist(taskId, state, trace)
            }
        ] as AttemptPersistence
    }

    static String pendingAnswer(String key = KEY) {
        StandIn.data('executor-result#pending').trim().replace('reference-deferred', key)
    }

    protected List<String> parents(String commit) {
        gitOutput(clone, 'rev-list', '--parents', '-n', '1', commit).split(' ').drop(1).toList()
    }

    protected TaskState stateAt(String commit) {
        StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(
                        gitOutput(clone, 'show', "${commit}:.gnomish-task/state.json"))))
    }

    protected List<String> subjectsSince(String commit) {
        gitOutput(clone, 'log', '--reverse', '--format=%s', "${commit}..refs/heads/${BRANCH}").readLines()
    }

    protected AttemptRecord recordAtTip() {
        StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(
                        gitOutput(clone, 'show', "refs/heads/${BRANCH}:.gnomish-task/state.json")))).attempts().last()
    }
}
