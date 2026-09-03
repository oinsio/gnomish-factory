package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.agent.CliJudgeVoter;
import com.github.oinsio.gnomish.adapter.agent.CliStageExecutor;
import com.github.oinsio.gnomish.adapter.agent.CompositeAgentProgressListener;
import com.github.oinsio.gnomish.adapter.agent.LoggingAgentProgressListener;
import com.github.oinsio.gnomish.adapter.agent.ResumeVerificationStageExecutor;
import com.github.oinsio.gnomish.adapter.console.InteractiveJudgeVoter;
import com.github.oinsio.gnomish.adapter.console.InteractiveStageExecutor;
import com.github.oinsio.gnomish.adapter.console.StageBriefing;
import com.github.oinsio.gnomish.adapter.law.PipelineLaw;
import com.github.oinsio.gnomish.app.console.DialogConsole;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces;
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.engine.time.SystemClock;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.status.AgentActivityEnricher;
import com.github.oinsio.gnomish.status.StatusSnapshotHolder;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Selects the {@link StageExecutor}/{@link JudgeVoter} adapter pair {@link RunAssembly}
 * wires into {@code EnginePorts} (FR10, design D6 of add-agent-executor): the interactive console
 * adapter when {@code --interactive} covers the role, else the manifest-driven CLI adapter — every
 * stage reaching the engine is {@code agent-cli} by construction. Extracted from {@link
 * RunAssembly} purely to keep both files within the project's file-size guidance
 * (`.claude/rules/process-invariants.md`); the behavior is unchanged.
 *
 * <p>Implements FR7, FR10, D6, D10 of add-agent-executor.
 */
final class ExecutorAdapterSelector {

    private ExecutorAdapterSelector() {}

    /**
     * Selects the stage executor: the interactive console adapter when {@code interactiveMode} is
     * {@code ALL} or {@code EXECUTOR_ONLY} (FR10, design D6), else the manifest-driven CLI adapter
     * wired with the renderer + status-enricher composite progress listener (task 9.4, FR7, D10)
     * bound to {@code holder}.
     */
    static StageExecutor stageExecutor(
            DialogConsole console,
            RunArguments.InteractiveMode interactiveMode,
            StatusSnapshotHolder holder,
            ManualRunAssembly assembly,
            ChildEnvAllowlist childEnv,
            PipelineLaw law) {
        return switch (interactiveMode) {
            case ALL, EXECUTOR_ONLY -> new InteractiveStageExecutor(console, new StageBriefing(law));
            case NONE, JUDGE_ONLY -> cliStageExecutor(holder, assembly, childEnv, law);
        };
    }

    /**
     * The manifest-driven CLI executor: the host construction unchanged, or — in container mode
     * (the integration pass of add-sandbox-core) — rounds routed through the sandboxed round
     * source and wrapped by {@link ResumeVerificationStageExecutor}, so an interrupted
     * verification found on resume re-verifies its harvested attempt commit without an agent
     * re-run (FR21, D15).
     */
    private static StageExecutor cliStageExecutor(
            StatusSnapshotHolder holder, ManualRunAssembly assembly, ChildEnvAllowlist childEnv, PipelineLaw law) {
        SandboxRunPieces sandbox = assembly.sandbox;
        if (sandbox == null) {
            // Sandbox pieces win by construction (design D3 of wire-host-mid-round-push): the
            // host-git decoration is consumed only on this branch, so a run carrying both seams
            // cannot double-wire its rounds. The decoration defaults to identity, so applying it
            // unconditionally IS the previous host construction when nothing was attached.
            return new CliStageExecutor(
                    assembly.factoryProperties,
                    assembly.systemClock,
                    executorProgressListener(holder),
                    law,
                    assembly.hostGitPush.apply(CliStageExecutor.hostRounds(assembly.systemClock, childEnv)));
        }
        var cli = new CliStageExecutor(
                assembly.factoryProperties,
                assembly.systemClock,
                executorProgressListener(holder),
                law,
                sandbox.executorRounds());
        return new ResumeVerificationStageExecutor(cli, sandbox.attemptCommit(), sandbox.pendingVerification());
    }

    /**
     * Selects the judge voter: the interactive console adapter when {@code interactiveMode} is
     * {@code ALL} or {@code JUDGE_ONLY} (FR10, design D6), else the manifest-driven CLI adapter
     * wired with the shared renderer alone (task 9.4, design D10): a judge round runs under the
     * verifying activity, so the executor-only status enricher is deliberately left out.
     */
    static JudgeVoter judgeVoter(
            DialogConsole console,
            RunArguments.InteractiveMode interactiveMode,
            FactoryProperties factoryProperties,
            SystemClock systemClock,
            ChildEnvAllowlist childEnv,
            PipelineLaw law,
            @Nullable SandboxRunPieces sandbox) {
        return switch (interactiveMode) {
            case ALL, JUDGE_ONLY -> new InteractiveJudgeVoter(console, law);
            case NONE, EXECUTOR_ONLY ->
                new CliJudgeVoter(
                        factoryProperties,
                        systemClock,
                        new LoggingAgentProgressListener(),
                        childEnv,
                        law,
                        sandbox == null ? null : sandbox.judgeEnvironments());
        };
    }

    /**
     * Builds the executor's progress listener (task 9.4, design D10): the shared {@link
     * LoggingAgentProgressListener} renderer plus an {@link AgentActivityEnricher} bound to {@code
     * holder}, fanned out via {@link CompositeAgentProgressListener}.
     */
    private static AgentProgressListener executorProgressListener(StatusSnapshotHolder holder) {
        return new CompositeAgentProgressListener(
                List.of(new LoggingAgentProgressListener(), new AgentActivityEnricher(holder)));
    }
}
