package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.check.http.HttpRunContext
import com.github.oinsio.gnomish.adapter.git.*
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.app.port.agent.RoundClose
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
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
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
 * The identity spec of a pending {@code transform} round resumed by a second instance (FR11, NFR-R3,
 * NFR-R4 of extract-agent-round-runner; design D11, "Resume"; crash-consistency item 14), on a real bare
 * origin, on the host and in container mode: a first instance runs the round's {@code start}, which
 * answers {@code pending}, lands the pending round commit and polls once, and dies before its next poll;
 * a second instance reads the register through the production recogniser, restores the round cell at its
 * assembly ({@link ExecutorAdapterSelector#stageRounds}) and runs the engine from the registered state.
 * The round it closes is the round that went pending: one token on every commit and on the record, the
 * participants of the one executor, and the ledger lines naming one round.
 */
class PendingRoundIdentitySpec extends RoundFlowSpecBase {

    static final String KEY = 'job-7'
    static final String REGISTER = 'refs/gnomish-factory/' + TASK

    /** The instance that went pending dies at its next poll, before the request reaches the executor. */
    static final class Killed extends RuntimeException {
        Killed() {
            super('the first instance was killed')
        }
    }

    Path link
    String created
    final TaskGit git = TaskGitFixture.real(runner)

    def setup() {
        addConvergedOrigin(clone, tempDir)
        assert runner.run(clone, 'push', '--atomic', 'origin', "refs/heads/${BRANCH}:refs/heads/${BRANCH}",
        "${REGISTER}:${REGISTER}").exitCode() == 0
        created = tip()
        link = StandIn.recording(Files.createDirectories(tempDir.resolve('bin')), 'transform-scripted')
        def answers = [pendingAnswer()] * 2 << StandIn.data('executor-result#transform').trim()
        Files.writeString(StandIn.beside(link, 'script'), answers.join('\n') + '\n')
    }

    // Scenario: lifecycle/task-branch-contract / A pending round is resumed by the same owner
    // Scenario: git/git-task-persistence / A pending commit above a pending-round snapshot is a pending round
    def "FR11, NFR-R4: in #mode mode, a pending round closed by a second instance is the round that went pending"() {
        given: 'the first instance went pending, polled once and died'
        def first = new RecordingEventListener()
        def killed = mode == 'host' ? hostRun(first, null, true) : boxRun(first, null, 'box-a', true)
        def token = RoundToken.of(created)

        expect: 'it died at its second poll; the register names its pending round commit'
        killed instanceof TaskOutcome.Escalated
        subjectsSince(created) == [
            ServiceCommitMessages.pendingSnapshot('build', 0, token),
            ServiceCommitMessages.pending('build', 0, token)
        ]
        gitOutput(clone, 'rev-parse', REGISTER) == tip()

        when: 'a second instance picks the task up through the production recogniser'
        def pending = mode == 'host'
                ? NoSalvageOverVerification.onHost(git.worktrees(), tempDir.resolve('worktree'), TASK, false)
                : new SnapshotTipCheck(runner, clone).inspect(TASK).orElse(null)
        def second = new RecordingEventListener()
        def outcome = mode == 'host' ? hostRun(second, pending, false) : boxRun(second, pending, 'box-b', false)

        then: 'the recogniser read a pending round of the round\'s token, above its pending-round snapshot'
        pending.close() == RoundClose.PENDING
        pending.token() == token

        and: 'the second instance made no start: its one request was a poll of the recorded key'
        requests()*.take(2)*.join(' ') == [
            'start -',
            'poll ' + KEY,
            'poll ' + KEY
        ]

        and: 'no verify check ran before the round closed: the branch holds the round\'s four commits, one token'
        outcome instanceof TaskOutcome.Completed
        subjectsSince(created) == [
            ServiceCommitMessages.pendingSnapshot('build', 0, token),
            ServiceCommitMessages.pending('build', 0, token),
            ServiceCommitMessages.snapshot('build', 0, token),
            'gnomish: round build#0'
        ]
        gitOutput(clone, 'rev-parse', REGISTER) == tip()

        and: 'the closed attempt carries the token the round went pending with, verified once, no pending entry left'
        def closing = stateAtTip()
        closing.pendingPolls().isEmpty()
        def record = closing.attempts().last()
        record.roundToken() == token
        record.result() == AttemptRecord.Result.PASSED
        record.checkResults().size() == 1

        and: 'every participant of the record is the one executor the round went pending on, in its transform role'
        def wentPending = finished(first)
        def closed = finished(second)
        record.usage()*.executor().toSet() == [
            wentPending.usage().executor()
        ] as Set
        record.usage()*.role().toSet() == [wentPending.usage().role()] as Set

        and: 'the ledger lines name one round: its key and executor, pending with the key it is resumed by'
        closed.key() == wentPending.key()
        closed.executor() == wentPending.executor()
        closed.resumedKey() == UntrustedText.subprocess(KEY)
        closed.pendingKey() == null
        first.events.findAll {
            it instanceof EngineEvent.PendingPolled
        }*.key().toSet() == [wentPending.key()] as Set
        second.events.findAll {
            it instanceof EngineEvent.PendingPolled
        }*.turn() == [PendingTurn.Origin.RECORDED]

        where:
        mode << ['host', 'container']
    }

    /** One instance's host run as a git-mode {@code run} assembles it, over the task's one worktree. */
    private TaskOutcome hostRun(RecordingEventListener listener, PendingVerification pending, boolean killAtSecondPoll) {
        def workingCopy = tempDir.resolve('worktree')
        if (!Files.exists(workingCopy)) {
            worktree()
        }
        def clock = new VirtualClock()
        def run = RunRound.of(pending)
        def assembly = newAssembly(FakeAgentSupport.propertiesFor('plain-round'), VirtualTimeEquipment.on(clock))
                .withHostGitPush(git.midRoundPush(), run)
        def persistence = git.store().attemptPersistence(workingCopy, TASK, run.rounds())
        flow(assembly, workingCopy, persistence, new DirectoryWorkspace(workingCopy), listener, killAtSecondPoll)
    }

    /** One instance's box run as {@code ContainerRunSupport} assembles it, in a box of its own seeded at the register. */
    private TaskOutcome boxRun(RecordingEventListener listener, PendingVerification pending, String boxName, boolean killAtSecondPoll) {
        def clock = new VirtualClock(LocalBoxEnvironment.EXEC_STAMP.plusSeconds(3600))
        def box = new LocalBoxEnvironment(clone, Files.createDirectories(tempDir.resolve(boxName)))
        def cell = new CurrentRound()
        def lease = new EnvironmentLease({ -> box }, BRANCH, [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), pipeline().stages())
        ])
        def pieces = new SandboxRunPieces(new SandboxRoundEnvironmentSource(lease, runner, clone, TASK, cell, clock),
                null, null, objects(), new RemoteAttemptDelivery(runner, clone, TASK), cell, pending)
        def assembly = newAssembly(FakeAgentSupport.propertiesFor('plain-round'), VirtualTimeEquipment.on(clock)).withSandbox(pieces)
        def persistence = new PushBestEffortAttemptPersistence(
                new EnvironmentAttemptPersistence(box, runner, clone, objects(), cell, git.epochs()),
                new BestEffortClosingPush(runner), clone)
        flow(assembly, clone, persistence, new RecordedAttemptCommitWorkspace(cell), listener, killAtSecondPoll)
    }

    /**
     * The engine over the run's assembled port, from the state at the register, as a resume reads it;
     * {@code killAtSecondPoll} makes the instance die at its second poll, before the request leaves it.
     */
    private TaskOutcome flow(ManualRunAssembly assembly, Path lawRoot, AttemptPersistence persistence, Workspace workspace,
            RecordingEventListener listener, boolean killAtSecondPoll) {
        def definition = pipeline()
        def binding = LawBinding.workingTree(clone)
        def described = assembly.describe(new RunOrder(lawRoot, null, definition, false), new DescribeMedium.Host(lawRoot),
                [], binding) as DescribedLaw
        def childEnv = assembly.checks.childEnv(assembly.sandboxProperties.envPassthrough(), definition, [])
        def stageRounds = ExecutorAdapterSelector.stageRounds(assembly, childEnv, LAW, described, definition,
                HttpRunContext.none(), RunLaw.open(binding).pinCheck())
        def port = killAtSecondPoll ? dyingAtSecondPoll(stageRounds.port()) : stageRounds.port()
        def ports = new EnginePorts(port, listener, persistence, assembly.time,
                assembly.sandbox == null ? AttemptDelivery.assumedDelivered() : assembly.sandbox.attemptDelivery(),
                described.executors(), stageRounds.identity())
        new Engine().run(definition, FakeAgentSupport.defaultTaskContext(), stateAtRegister(), workspace, ports)
    }

    private static StageExecutor dyingAtSecondPoll(StageExecutor port) {
        int polls = 0
        [execute: { StageExecutor.Request request -> port.execute(request) },
            poll: { StageExecutor.Request request, PendingTurn turn ->
                if (++polls> 1) {
                    throw new Killed()
                }
                port.poll(request, turn)
            }] as StageExecutor
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

    private static String pendingAnswer() {
        StandIn.data('executor-result#pending').trim().replace('reference-deferred', KEY)
    }

    private List<List<String>> requests() {
        StandIn.beside(link, 'heads').toFile().readLines()*.split(' ')*.toList()
    }

    private static EngineEvent.ExecutionFinished finished(RecordingEventListener listener) {
        listener.events.find {
            it instanceof EngineEvent.ExecutionFinished
        } as EngineEvent.ExecutionFinished
    }

    private List<String> subjectsSince(String commit) {
        gitOutput(clone, 'log', '--reverse', '--format=%s', "${commit}..refs/heads/${BRANCH}").readLines()
    }

    /** The state at the register, through the one {@code state.json} parse every reader of the branch takes. */
    private TaskState stateAtRegister() {
        stateAt(REGISTER)
    }

    private TaskState stateAtTip() {
        stateAt("refs/heads/${BRANCH}")
    }

    private TaskState stateAt(String ref) {
        StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(
                        gitOutput(clone, 'show', "${ref}:.gnomish-task/state.json"))))
    }
}
