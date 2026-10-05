package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.agent.CliJudgeVoter;
import com.github.oinsio.gnomish.adapter.agent.CliStageExecutor;
import com.github.oinsio.gnomish.adapter.agent.CompositeAgentProgressListener;
import com.github.oinsio.gnomish.adapter.agent.LoggingAgentProgressListener;
import com.github.oinsio.gnomish.adapter.agent.ResumeVerificationStageExecutor;
import com.github.oinsio.gnomish.adapter.law.PipelineLaw;
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
 * Binds the {@link StageExecutor}/{@link JudgeVoter} pair {@link RunAssembler} wires into {@code
 * EnginePorts} to the run's execution medium: the manifest-driven CLI adapters are the only binding
 * (FR2 of remove-interactive-console) — every stage reaching the engine is {@code agent-cli} by
 * construction — and what this class decides is where they run. On the host, the executor's rounds
 * carry the host-git decoration; in container mode they go through the sandboxed round source, the
 * executor is wrapped for resume re-verification, and the judge votes in the sandbox's judge
 * environments. The executor and the judge get deliberately different progress listeners.
 *
 * <p>Implements FR7, D6, D10 of add-agent-executor; FR2 of remove-interactive-console.
 */
final class ExecutorAdapterSelector {

    private ExecutorAdapterSelector() {}

    /**
     * The manifest-driven CLI executor, wired with the renderer + status-enricher composite
     * progress listener (task 9.4, FR7, D10) bound to {@code holder}: the host construction, or —
     * in container mode (the integration pass of add-sandbox-core) — rounds routed through the
     * sandboxed round source and wrapped by {@link ResumeVerificationStageExecutor}, so an interrupted
     * verification found on resume re-verifies its harvested attempt commit without an agent
     * re-run (FR21, D15).
     */
    static StageExecutor stageExecutor(
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
     * The manifest-driven CLI judge voter, wired with the shared renderer alone (task 9.4, design D10): a judge round runs under the
     * verifying activity, so the executor-only status enricher is deliberately left out.
     */
    static JudgeVoter judgeVoter(
            FactoryProperties factoryProperties,
            SystemClock systemClock,
            ChildEnvAllowlist childEnv,
            PipelineLaw law,
            @Nullable SandboxRunPieces sandbox) {
        return new CliJudgeVoter(
                factoryProperties,
                systemClock,
                new LoggingAgentProgressListener(),
                childEnv,
                law,
                sandbox == null ? null : sandbox.judgeEnvironments());
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
