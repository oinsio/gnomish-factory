package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.CliJudgeVoter
import com.github.oinsio.gnomish.adapter.agent.CliStageExecutor
import com.github.oinsio.gnomish.adapter.agent.ResumeVerificationStageExecutor
import com.github.oinsio.gnomish.adapter.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.adapter.console.DialogConsole
import com.github.oinsio.gnomish.adapter.console.InteractiveJudgeVoter
import com.github.oinsio.gnomish.adapter.console.InteractiveStageExecutor
import com.github.oinsio.gnomish.adapter.console.SystemConsoleIO
import com.github.oinsio.gnomish.adapter.engine.SystemClock
import com.github.oinsio.gnomish.adapter.environment.ChildEnvAllowlist
import com.github.oinsio.gnomish.adapter.git.AttemptCommitRef
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.status.StatusSnapshotHolder
import spock.lang.Specification

/**
 * FR7, FR10, D6, D10 of add-agent-executor: {@link ExecutorAdapterSelector}'s stage-executor and
 * judge-voter selection — the interactive console adapter for the covering roles, the
 * manifest-driven CLI adapter otherwise, with a genuinely non-null, correctly-shaped instance
 * either way (host {@link CliStageExecutor} directly, or wrapped by {@link
 * ResumeVerificationStageExecutor} in container mode).
 */
class ExecutorAdapterSelectorSpec extends Specification implements AppAssemblyFixture {

    def console = new DialogConsole(new SystemConsoleIO(
    new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream())),
    { json -> 'unused' })
    def holder = new StatusSnapshotHolder(TaskState.atStageStart('build'), 3)
    def law = PipelineLaw.ofContent([:])
    def childEnv = ChildEnvAllowlist.none()

    private static SandboxRunPieces pieces() {
        new SandboxRunPieces(
                { req -> null } as RoundEnvironmentSource,
                null, null, null, null,
                new AttemptCommitRef(), null)
    }

    // FR7, D6, D10: NONE/JUDGE_ONLY (the executor is not interactive) selects the manifest-driven
    // host CLI executor when no sandbox pieces are supplied — the NO_COVERAGE "return null" mutant
    // of the private cliStageExecutor helper is killed by asserting a real, typed instance.
    def "stageExecutor selects a real host CliStageExecutor when the executor is non-interactive and host-mode"() {
        when:
        def executor = ExecutorAdapterSelector.stageExecutor(
                console, RunArguments.InteractiveMode.NONE, holder, testProperties(),
                new SystemClock(), childEnv, law, null)

        then:
        executor != null
        executor instanceof CliStageExecutor
    }

    // FR21, D15: with sandbox pieces supplied, the CLI executor is wrapped by
    // ResumeVerificationStageExecutor — a different branch of the same helper the "return null"
    // mutant would also collapse, so both shapes are pinned down explicitly.
    def "stageExecutor wraps the CLI executor with ResumeVerificationStageExecutor in container mode"() {
        when:
        def executor = ExecutorAdapterSelector.stageExecutor(
                console, RunArguments.InteractiveMode.NONE, holder, testProperties(),
                new SystemClock(), childEnv, law, pieces())

        then:
        executor != null
        executor instanceof ResumeVerificationStageExecutor
    }

    // FR10, D6: ALL/EXECUTOR_ONLY selects the interactive console adapter regardless of sandbox
    // pieces — the switch's other arm, kept alongside the CLI-selection tests for contrast.
    def "stageExecutor selects the interactive adapter when the executor role is interactive"() {
        when:
        def executor = ExecutorAdapterSelector.stageExecutor(
                console, RunArguments.InteractiveMode.ALL, holder, testProperties(),
                new SystemClock(), childEnv, law, null)

        then:
        executor instanceof InteractiveStageExecutor
    }

    // FR10, D6: the judge-voter twin of the same selection, host-mode.
    def "judgeVoter selects the manifest-driven CLI judge when the judge is non-interactive"() {
        when:
        def voter = ExecutorAdapterSelector.judgeVoter(
                console, RunArguments.InteractiveMode.NONE, testProperties(),
                new SystemClock(), childEnv, law, null)

        then:
        voter != null
        voter instanceof CliJudgeVoter
    }

    def "judgeVoter selects the interactive adapter when the judge role is interactive"() {
        when:
        def voter = ExecutorAdapterSelector.judgeVoter(
                console, RunArguments.InteractiveMode.ALL, testProperties(),
                new SystemClock(), childEnv, law, null)

        then:
        voter instanceof InteractiveJudgeVoter
    }
}
