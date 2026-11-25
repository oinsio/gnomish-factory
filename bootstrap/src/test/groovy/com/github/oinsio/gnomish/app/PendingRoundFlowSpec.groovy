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
 * The flow spec of a pending {@code transform} round (FR10, NFR-R4 of extract-agent-round-runner; design
 * D11 as amended 2026-11-25, task 6.4a): the {@link Engine} over the stage chain a run assembles through
 * its production owners ({@link ExecutorAdapterSelector#stageRounds}) — on the host the host-git
 * decoration and {@link GitAttemptPersistence}; in a box the sandboxed round source over a {@link
 * LocalBoxEnvironment} and the container persistence behind its closing push — with a real bare origin.
 * The stage runs the {@code transform-scripted} stand-in, which declares {@code poll}: {@code start}
 * answers {@code pending}, two polls {@code pending} with the recorded key, the third {@code completed};
 * the round closes and its one {@code files-exist} check runs.
 */
class PendingRoundFlowSpec extends RoundFlowSpecBase {

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
        def answers = [pendingAnswer()] * 3 << StandIn.data('executor-result#transform').trim()
        Files.writeString(StandIn.beside(link, 'script'), answers.join('\n') + '\n')
    }

    // Scenario: pipeline/stage-engine / A poll that answers pending writes nothing
    def "FR10: in #mode mode, a poll of a pending round that answers pending writes nothing and runs in the start's environment"() {
        when:
        def outcome = mode == 'host' ? runHost() : runBox()

        then: 'one pending round commit, written once and registered with the branch, then the round\'s close'
        writes*.op == ['pending', 'round']
        def pendingCommit = writes[0].tip
        writes[0].register == pendingCommit

        and: 'the polls that answered pending moved neither the branch nor its register: the close registered its snapshot right on the pending round commit'
        writes[1].tip == writes[1].register
        gitOutput(clone, 'rev-list', '--parents', '-n', '1', writes[1].register as String).split(' ').drop(1).toList() == [pendingCommit]

        and: 'the close was recorded: the round completed'
        outcome instanceof TaskOutcome.Completed

        and: 'every request ran in the environment the start ran in'
        def requests = StandIn.beside(link, 'heads').toFile().readLines()*.split(' ')*.toList()
        requests*.take(2)*.join(' ') == ['start -'] + ['poll ' + KEY] * 3
        requests*.getAt(3).toSet().size() == 1

        where:
        mode << ['host', 'container']
    }

    // Scenario: pipeline/stage-engine / Every commit of a pending round names one token
    // Scenario: git/git-task-persistence / Only the start of a round registers a pending-round snapshot
    def "FR10: in #mode mode, every commit of a round polled twice and closed by a completed poll names the token the round opened with"() {
        given:
        def token = RoundToken.of(created)

        when:
        def outcome = mode == 'host' ? runHost() : runBox()

        then: 'the branch holds exactly the round\'s four commits, each subject naming its one token'
        subjectsSince(created) == [
            ServiceCommitMessages.pendingSnapshot('build', 0, token),
            ServiceCommitMessages.pending('build', 0, token),
            ServiceCommitMessages.snapshot('build', 0, token),
            'gnomish: round build#0'
        ]

        and: 'the round closed, its verify chain ran and passed with no boundary violation, and its record names the token'
        outcome instanceof TaskOutcome.Completed
        def record = recordAtTip()
        record.result() == AttemptRecord.Result.PASSED
        record.checkResults().size() == 1
        record.roundToken() == token
        gitOutput(clone, 'rev-parse', REGISTER) == tip()

        where:
        mode << ['host', 'container']
    }

    /** The host run as a git-mode {@code run} assembles it (cf. {@link RoundFlowSpecBase#runHostAssembled}). */
    private TaskOutcome runHost() {
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
    private TaskOutcome runBox() {
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

    private TaskOutcome flow(ManualRunAssembly assembly, Path lawRoot, AttemptPersistence persistence, Workspace workspace) {
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
    private PipelineDefinition pipeline() {
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
    private AttemptPersistence observed(AttemptPersistence persistence) {
        [
            persistPending: { String taskId, TaskState state ->
                persistence.persistPending(taskId, state)
                writes << [op: 'pending', tip: tip(), register: gitOutput(clone, 'rev-parse', REGISTER)]
            },
            persist: { String taskId, TaskState state, ToolTrace trace ->
                writes << [op: 'round', tip: tip(), register: gitOutput(clone, 'rev-parse', REGISTER)]
                persistence.persist(taskId, state, trace)
            }
        ] as AttemptPersistence
    }

    private static String pendingAnswer() {
        StandIn.data('executor-result#pending').trim().replace('reference-deferred', KEY)
    }

    private List<String> subjectsSince(String commit) {
        gitOutput(clone, 'log', '--reverse', '--format=%s', "${commit}..refs/heads/${BRANCH}").readLines()
    }

    private AttemptRecord recordAtTip() {
        StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(
                        gitOutput(clone, 'show', "refs/heads/${BRANCH}:.gnomish-task/state.json")))).attempts().last()
    }
}
