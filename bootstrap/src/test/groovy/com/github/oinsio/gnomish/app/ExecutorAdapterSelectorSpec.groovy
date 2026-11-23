package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.ResumeVerificationStageExecutor
import com.github.oinsio.gnomish.adapter.check.PinCheck
import com.github.oinsio.gnomish.adapter.check.http.HttpRunContext
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.git.HostGitRounds
import com.github.oinsio.gnomish.app.port.git.PendingVerification
import com.github.oinsio.gnomish.app.port.git.RunRound
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR7, D6, D10 of add-agent-executor; FR2 of remove-interactive-console: {@link
 * ExecutorAdapterSelector} binds the run's name-keyed dispatch (design D8 of define-executor-contract)
 * to the run's execution medium, with a genuinely non-null, correctly-shaped instance either way (the
 * in-place host {@link ExecutorDispatch} directly, or wrapped by {@link ResumeVerificationStageExecutor}
 * in container mode and in a git-mode host run).
 */
class ExecutorAdapterSelectorSpec extends Specification implements AppAssemblyFixture {

    @TempDir
    Path workingCopy

    def law = PipelineLaw.ofContent([:])
    def childEnv = TestChildEnvironment.hostAllowlist()
    def described = new DescribedLaw(LawBinding.workingTree(Path.of('/tmp/law')), [:])
    def definition = new PipelineDefinition('1', new AutonomyLimits(3), [], null, [])

    /** The pin-check of a run whose law is a working tree with no repository: no check here declares a pin. */
    static final PinCheck PINS = RunLaw.open(LawBinding.workingTree(Path.of('/tmp/law'))).pinCheck()

    private static SandboxRunPieces pieces() {
        new SandboxRunPieces(
                [openRound: { req ->
                        null
                    }, identity: {
                        RoundIdentity.inPlace()
                    }] as RoundEnvironmentSource,
                null, null, null, null,
                new CurrentRound(), null)
    }

    // FR7, D6, D10; FR2 of remove-interactive-console: the manifest-driven host CLI executor when
    // no sandbox pieces are supplied — the "return null" mutant is killed by asserting a real,
    // typed instance.
    def "stageExecutor binds the dispatch directly in host mode"() {
        when:
        def executor = ExecutorAdapterSelector.stageRounds(newAssembly(), childEnv, law, described, definition, HttpRunContext.none(), PINS).executor()

        then:
        executor != null
        executor instanceof ExecutorDispatch
    }

    // FR3 of wire-host-mid-round-push (design D3): the assembly's host-git decoration is applied
    // to the host rounds exactly once when no sandbox pieces are attached — the operator sees the
    // real host source and its return is what the executor is built over.
    def "stageExecutor applies the host-git decoration to the host rounds and the stage chain when no sandbox is attached"() {
        given:
        def applied = []
        def chains = []
        def answer = ExecutionResult.completed(ExecutorUsage.none(), new ToolTrace(new AttemptKey('PROJ-1', 'build', 0), []), [])
        def wrapped = Stub(StageExecutor) {
            execute(_) >> answer
        }
        def run = RunRound.of(null)
        def decoration = Stub(HostGitRounds) {
            decorate(_, _) >> { RoundEnvironmentSource rounds, CurrentRound cell ->
                applied << [rounds, cell]; rounds
            }
            snapshotting(_, _) >> { StageExecutor chain, CurrentRound cell ->
                chains << [chain, cell]; wrapped
            }
        }
        def assembly = newAssembly().withHostGitPush(decoration, run)

        when:
        def executor = ExecutorAdapterSelector.stageRounds(assembly, childEnv, law, described, definition, HttpRunContext.none(), PINS).executor()

        then: 'the round source is decorated over the run\'s cell'
        applied.size() == 1
        applied[0][0] instanceof RoundEnvironmentSource
        applied[0][1].is(run.rounds())

        and: 'the dispatch is wrapped by the snapshot decoration over the same cell (design D22)'
        chains.size() == 1
        chains[0][0] instanceof ExecutorDispatch
        chains[0][1].is(run.rounds())

        and: 'the resume decorator sits above it: with no interrupted verification every round reaches the snapshot chain'
        executor instanceof ResumeVerificationStageExecutor
        executor.execute(request('build', 0)).is(answer)
    }

    // FR19 of migrate-verify-to-executor-contract (design D22): the git-mode host run's stage chain
    // is wrapped by the resume decorator over the run's cell and the interrupted verification the
    // no-salvage owner found, so the interrupted round re-verifies without its stage executor.
    def "stageExecutor resumes a host interrupted verification over the run's cell without the stage chain"() {
        given:
        def run = RunRound.of(new PendingVerification('snap', 'build', 0, RoundToken.of('0a1b2c'), Optional.empty(), 'snap', ['snap']))
        def chain = Mock(StageExecutor)
        def decoration = Stub(HostGitRounds) {
            decorate(_, _) >> { RoundEnvironmentSource rounds, CurrentRound cell ->
                rounds
            }
            snapshotting(_, _) >> chain
        }
        def executor = ExecutorAdapterSelector.stageRounds(newAssembly().withHostGitPush(decoration, run), childEnv, law,
                described, definition, HttpRunContext.none(), PINS).executor()

        when:
        def result = executor.execute(request('build', 0))

        then: 'no stage chain ran; the round goes on to verification of the snapshot, restored in the run\'s cell'
        0 * chain.execute(_)
        result.status() instanceof ExecutionResult.Completed
        run.rounds().closed().attemptCommit() == 'snap'
    }

    private static StageExecutor.Request request(String stage, int attempt) {
        new StageExecutor.Request(new TaskContext('PROJ-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
        new StageDefinition(stage, 'purpose', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
        'instructions.md', [], new AutonomyLimits(1), AdvancementMode.AUTO),
        new FakeWorkspace(), attempt, [])
    }

    // FR3, design D3/Risks: sandbox pieces win by construction — a run carrying both seams
    // consults only the sandbox rounds, so the host-git decoration is never applied.
    def "sandbox pieces win by construction over the host-git decoration"() {
        given:
        def applied = []
        def decoration = Stub(HostGitRounds) {
            decorate(_, _) >> { RoundEnvironmentSource rounds, CurrentRound cell ->
                applied << rounds; rounds
            }
            snapshotting(_, _) >> { StageExecutor chain, CurrentRound cell ->
                applied << chain; chain
            }
        }
        def assembly = newAssembly().withHostGitPush(decoration, RunRound.of(null)).withSandbox(pieces())

        when:
        def executor = ExecutorAdapterSelector.stageRounds(assembly, childEnv, law, described, definition, HttpRunContext.none(), PINS).executor()

        then:
        executor instanceof ResumeVerificationStageExecutor
        applied.isEmpty()
    }

    // FR21, D15: with sandbox pieces supplied, the CLI executor is wrapped by
    // ResumeVerificationStageExecutor — a different branch of the same helper the "return null"
    // mutant would also collapse, so both shapes are pinned down explicitly.
    def "stageExecutor wraps the dispatch with ResumeVerificationStageExecutor in container mode"() {
        when:
        def executor = ExecutorAdapterSelector.stageRounds(newAssembly().withSandbox(pieces()), childEnv, law, described, definition, HttpRunContext.none(), PINS).executor()

        then:
        executor != null
        executor instanceof ResumeVerificationStageExecutor
    }

    // FR1, FR2 of migrate-verify-to-executor-contract (D18 as amended 2026-11-12): the port routes a
    //     check round to the check dispatch outside the stage chain — the container chain here is the
    //     resume decorator over a dispatch whose round source opens no round, so a check request that
    //     reached any of it would fail instead of answering.
    def "a check round through the port reaches the check dispatch, never the resume-wrapped stage chain"() {
        given:
        def check = VerifyChecks.builtin('files', [files: ['absent.txt']])
        def law = new PipelineDefinition('1', new AutonomyLimits(3), [
            new StageDefinition('build', 'p', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
            'i.md', [check], new AutonomyLimits(3), AdvancementMode.AUTO)
        ], null, [])
        def rounds = ExecutorAdapterSelector.stageRounds(newAssembly().withSandbox(pieces()), childEnv, this.law, described,
                law, HttpRunContext.none(), PINS)
        def request = new StageExecutor.Request(new TaskContext('TASK-1', UntrustedText.tracker('t'),
                UntrustedText.tracker('b'), []), law.stages()[0], new DirectoryWorkspace(workingCopy), 0, [], check, CheckRef.of(0, check), null)

        when:
        def result = rounds.port().execute(request)

        then: 'the files-exist built-in answered, its missing path an error result of the reply'
        rounds.executor() instanceof ResumeVerificationStageExecutor
        def answer = (result.answer() as ExecutionResult.VerdictReply).answer() as VerdictAnswer.Sarif
        answer.results()*.finding()*.location() == ['absent.txt']
    }
}
