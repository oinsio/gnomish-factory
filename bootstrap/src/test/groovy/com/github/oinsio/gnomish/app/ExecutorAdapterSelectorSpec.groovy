package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.CliJudgeVoter
import com.github.oinsio.gnomish.adapter.agent.CliStageExecutor
import com.github.oinsio.gnomish.adapter.agent.ResumeVerificationStageExecutor
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.status.StatusSnapshotHolder
import java.util.function.UnaryOperator
import spock.lang.Specification

/**
 * FR7, D6, D10 of add-agent-executor; FR2 of remove-interactive-console: {@link
 * ExecutorAdapterSelector} binds the manifest-driven CLI adapters to the run's execution medium,
 * with a genuinely non-null, correctly-shaped instance either way (host {@link CliStageExecutor}
 * directly, or wrapped by {@link ResumeVerificationStageExecutor} in container mode).
 */
class ExecutorAdapterSelectorSpec extends Specification implements AppAssemblyFixture {

    def holder = new StatusSnapshotHolder(TaskState.atStageStart('build'), 3)
    def law = PipelineLaw.ofContent([:])
    def childEnv = ChildEnvAllowlist.none()

    private static SandboxRunPieces pieces() {
        new SandboxRunPieces(
                { req -> null } as RoundEnvironmentSource,
                null, null, null, null,
                new AttemptCommitRef(), null)
    }

    // FR7, D6, D10; FR2 of remove-interactive-console: the manifest-driven host CLI executor when
    // no sandbox pieces are supplied — the "return null" mutant is killed by asserting a real,
    // typed instance.
    def "stageExecutor binds a real host CliStageExecutor in host mode"() {
        when:
        def executor = ExecutorAdapterSelector.stageExecutor(
                holder, newAssembly(), childEnv, law)

        then:
        executor != null
        executor instanceof CliStageExecutor
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
        def executor = ExecutorAdapterSelector.stageExecutor(
                holder, assembly, childEnv, law)

        then:
        executor instanceof CliStageExecutor
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
        def executor = ExecutorAdapterSelector.stageExecutor(
                holder, assembly, childEnv, law)

        then:
        executor instanceof ResumeVerificationStageExecutor
        applied.isEmpty()
    }

    // FR21, D15: with sandbox pieces supplied, the CLI executor is wrapped by
    // ResumeVerificationStageExecutor — a different branch of the same helper the "return null"
    // mutant would also collapse, so both shapes are pinned down explicitly.
    def "stageExecutor wraps the CLI executor with ResumeVerificationStageExecutor in container mode"() {
        when:
        def executor = ExecutorAdapterSelector.stageExecutor(holder, newAssembly().withSandbox(pieces()), childEnv, law)

        then:
        executor != null
        executor instanceof ResumeVerificationStageExecutor
    }

    // FR2 of remove-interactive-console: the judge-voter twin of the same binding, host-mode.
    def "judgeVoter binds the manifest-driven CLI judge in host mode"() {
        when:
        def voter = ExecutorAdapterSelector.judgeVoter(
                testProperties(), new SystemClock(), childEnv, law, null)

        then:
        voter != null
        voter instanceof CliJudgeVoter
    }
}
