package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.CliJudgeVoter
import com.github.oinsio.gnomish.adapter.agent.ResumeVerificationStageExecutor
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Path
import java.util.function.UnaryOperator
import spock.lang.Specification

/**
 * FR7, D6, D10 of add-agent-executor; FR2 of remove-interactive-console: {@link
 * ExecutorAdapterSelector} binds the run's name-keyed dispatch (design D8 of define-executor-contract)
 * to the run's execution medium, with a genuinely non-null, correctly-shaped instance either way (the
 * host {@link ExecutorDispatch} directly, or wrapped by {@link ResumeVerificationStageExecutor} in
 * container mode).
 */
class ExecutorAdapterSelectorSpec extends Specification implements AppAssemblyFixture {

    def law = PipelineLaw.ofContent([:])
    def childEnv = TestChildEnvironment.hostAllowlist()
    def described = new DescribedLaw(LawBinding.workingTree(Path.of('/tmp/law')), [:])
    def definition = new PipelineDefinition('1', new AutonomyLimits(3), [], null, [])

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
        def executor = ExecutorAdapterSelector.stageRounds(newAssembly(), childEnv, law, described, definition).executor()

        then:
        executor != null
        executor instanceof ExecutorDispatch
    }

    // FR3 of wire-host-mid-round-push (design D3): the assembly's host-git decoration is applied
    // to the host rounds exactly once when no sandbox pieces are attached — the operator sees the
    // real host source and its return is what the executor is built over.
    def "stageExecutor applies the host-git decoration to the host rounds when no sandbox is attached"() {
        given:
        def applied = []
        def decoration = { rounds ->
            applied << rounds; rounds
        } as UnaryOperator<RoundEnvironmentSource>
        def assembly = newAssembly().withHostGitPush(decoration)

        when:
        def executor = ExecutorAdapterSelector.stageRounds(assembly, childEnv, law, described, definition).executor()

        then:
        executor instanceof ExecutorDispatch
        applied.size() == 1
        applied[0] instanceof RoundEnvironmentSource
    }

    // FR3, design D3/Risks: sandbox pieces win by construction — a run carrying both seams
    // consults only the sandbox rounds, so the host-git decoration is never applied.
    def "sandbox pieces win by construction over the host-git decoration"() {
        given:
        def applied = []
        def decoration = { rounds ->
            applied << rounds; rounds
        } as UnaryOperator<RoundEnvironmentSource>
        def assembly = newAssembly().withHostGitPush(decoration).withSandbox(pieces())

        when:
        def executor = ExecutorAdapterSelector.stageRounds(assembly, childEnv, law, described, definition).executor()

        then:
        executor instanceof ResumeVerificationStageExecutor
        applied.isEmpty()
    }

    // FR21, D15: with sandbox pieces supplied, the CLI executor is wrapped by
    // ResumeVerificationStageExecutor — a different branch of the same helper the "return null"
    // mutant would also collapse, so both shapes are pinned down explicitly.
    def "stageExecutor wraps the dispatch with ResumeVerificationStageExecutor in container mode"() {
        when:
        def executor = ExecutorAdapterSelector.stageRounds(newAssembly().withSandbox(pieces()), childEnv, law, described, definition).executor()

        then:
        executor != null
        executor instanceof ResumeVerificationStageExecutor
    }

    // FR2 of remove-interactive-console: the judge-voter twin of the same binding, host-mode.
    def "judgeVoter binds the manifest-driven CLI judge in host mode"() {
        when:
        def voter = ExecutorAdapterSelector.judgeVoter(
                testProperties(), new VirtualClock(), childEnv, law, null)

        then:
        voter != null
        voter instanceof CliJudgeVoter
    }
}
