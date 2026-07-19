package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.agent.AgentProgressListener;
import com.github.oinsio.gnomish.adapter.agent.CliJudgeVoter;
import com.github.oinsio.gnomish.adapter.agent.CliStageExecutor;
import com.github.oinsio.gnomish.adapter.agent.CompositeAgentProgressListener;
import com.github.oinsio.gnomish.adapter.agent.LoggingAgentProgressListener;
import com.github.oinsio.gnomish.adapter.console.DialogConsole;
import com.github.oinsio.gnomish.adapter.console.InteractiveJudgeVoter;
import com.github.oinsio.gnomish.adapter.console.InteractiveStageExecutor;
import com.github.oinsio.gnomish.adapter.console.StageBriefing;
import com.github.oinsio.gnomish.adapter.engine.SystemClock;
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.status.AgentActivityEnricher;
import com.github.oinsio.gnomish.status.StatusSnapshotHolder;
import java.util.List;

/**
 * Selects the {@link StageExecutor}/{@link JudgeVoter} adapter pair {@link ManualRunAssembly}
 * wires into {@code EnginePorts} (FR10, design D6 of add-agent-executor): the interactive console
 * adapter when {@code --interactive} covers the role, else the manifest-driven CLI adapter — every
 * stage reaching the engine is {@code agent-cli} by construction. Extracted from {@link
 * ManualRunAssembly} purely to keep both files within the project's file-size guidance
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
            FactoryProperties factoryProperties,
            SystemClock systemClock) {
        return switch (interactiveMode) {
            case ALL, EXECUTOR_ONLY -> new InteractiveStageExecutor(console, new StageBriefing());
            case NONE, JUDGE_ONLY ->
                new CliStageExecutor(factoryProperties, systemClock, executorProgressListener(holder));
        };
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
            SystemClock systemClock) {
        return switch (interactiveMode) {
            case ALL, JUDGE_ONLY -> new InteractiveJudgeVoter(console);
            case NONE, EXECUTOR_ONLY ->
                new CliJudgeVoter(factoryProperties, systemClock, new LoggingAgentProgressListener());
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
