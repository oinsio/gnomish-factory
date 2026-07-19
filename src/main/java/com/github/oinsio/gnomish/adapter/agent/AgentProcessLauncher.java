package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Spawns one fresh {@code claude -p} round as a real subprocess (design D2,
 * D7): binary path from {@link FactoryProperties#agentCliBinary()}, cwd set
 * to the stage workspace's root. Command-line assembly (transport flags,
 * invocation options) is delegated to {@link AgentCommandLine} (design D2);
 * this class keeps only the {@code ProcessBuilder} start seam.
 *
 * <p>Environment: the child process inherits the current process's full
 * environment, which is {@link ProcessBuilder}'s default behaviour (the same
 * default {@link com.github.oinsio.gnomish.adapter.check.CommandProcessRunner}
 * relies on elsewhere in this codebase). {@link
 * FactoryProperties#agentCliEnvPassthrough()} names the Ollama-seam
 * variables (e.g. {@code ANTHROPIC_BASE_URL}, an auth token, a default-model
 * env var) an operator is expected to set in the environment this JVM itself
 * runs under; because inheritance already carries the full environment
 * through, no code here needs to selectively copy those names — the list is
 * documentation of operator intent (D7), not a runtime allowlist. Should a
 * future requirement call for isolating the child's environment to a strict
 * subset, this is the seam to change: replace the default inheritance with
 * an explicit {@code builder.environment()} rebuild from just the passthrough
 * names.
 *
 * <p>Implements FR1, FR6, FR12, D3, D7 of add-agent-executor.
 */
public final class AgentProcessLauncher {

    private final Clock clock;

    /**
     * @param clock the read-time source stamped onto {@link
     *     LaunchedAgentProcess#startedAt} immediately after the process
     *     starts (FR6, D3); never null (production wiring uses {@link
     *     com.github.oinsio.gnomish.adapter.engine.SystemClock}, tests a
     *     controllable fake)
     */
    public AgentProcessLauncher(Clock clock) {
        this.clock = clock;
    }

    /**
     * Builds the round's command line and starts it in {@code workspace}'s
     * root, without any invocation options (`--model`, settings flags).
     * Returns {@code null} rather than throwing when the process cannot even
     * be started (e.g. a misconfigured or missing binary path), mirroring
     * {@code CommandProcessRunner}'s precedent, so the caller can turn that
     * into an infrastructure failure of the round (NFR-R1) without a stack
     * trace.
     *
     * @param workspace the stage workspace whose root becomes the process's cwd
     * @param prompt the round's prompt text, passed via {@code -p}
     * @param factoryProperties resolved installation config: CLI binary path
     *     and env passthrough intent (D7)
     * @return the started process paired with its resolved command line, or
     *     {@code null} if the process failed to start
     */
    @Nullable
    public LaunchedAgentProcess launch(
            DirectoryWorkspace workspace, String prompt, FactoryProperties factoryProperties) {
        List<String> command = AgentCommandLine.transportOnly(factoryProperties.agentCliBinary(), prompt);
        return start(workspace, command);
    }

    /**
     * Builds the round's command line — transport flags plus the {@code
     * --model} and settings flags rendered by {@link AgentInvocationOptions}
     * for {@code model}/{@code settings} — and starts it in {@code
     * workspace}'s root. Returns {@code null} on process-start failure, same
     * contract as the transport-only overload.
     *
     * @param workspace the stage workspace whose root becomes the process's cwd
     * @param prompt the round's prompt text, passed via {@code -p}
     * @param factoryProperties resolved installation config: CLI binary path
     *     and env passthrough intent (D7)
     * @param model the pinned, non-blank model id ({@code executor.model} /
     *     {@code check.model()}), rendered as {@code --model} (FR11, D7)
     * @param settings the opaque, already-validated settings map (FR11);
     *     recognized keys render to CLI flags, others are ignored
     * @return the started process paired with its resolved command line, or
     *     {@code null} if the process failed to start
     */
    @Nullable
    public LaunchedAgentProcess launch(
            DirectoryWorkspace workspace,
            String prompt,
            FactoryProperties factoryProperties,
            String model,
            Map<String, Object> settings) {
        return launch(workspace, prompt, factoryProperties, model, settings, Map.of());
    }

    /**
     * Same as the model+settings overload, plus an {@code extraEnv} fragment
     * merged into the child process's environment on top of the default
     * inheritance — the seam task 6.5 uses to wire {@link
     * DecisionFileTransport.Handle#envFragment()}'s {@code
     * GNOMISH_DECISION_FILE} entry into the round without this launcher
     * knowing anything about the decision protocol (FR3, NFR-S2, D1). An
     * empty map is behaviourally identical to the five-argument overload.
     *
     * @param workspace the stage workspace whose root becomes the process's cwd
     * @param prompt the round's prompt text, passed via {@code -p}
     * @param factoryProperties resolved installation config: CLI binary path
     *     and env passthrough intent (D7)
     * @param model the pinned, non-blank model id, rendered as {@code --model}
     * @param settings the opaque, already-validated settings map (FR11)
     * @param extraEnv variables merged into the child process's environment
     *     on top of the inherited default (e.g. {@code GNOMISH_DECISION_FILE});
     *     never null, may be empty
     * @return the started process paired with its resolved command line, or
     *     {@code null} if the process failed to start
     */
    @Nullable
    public LaunchedAgentProcess launch(
            DirectoryWorkspace workspace,
            String prompt,
            FactoryProperties factoryProperties,
            String model,
            Map<String, Object> settings,
            Map<String, String> extraEnv) {
        List<String> command =
                AgentCommandLine.fromModelAndSettings(factoryProperties.agentCliBinary(), prompt, model, settings);
        return start(workspace, command, extraEnv);
    }

    /**
     * Same transport shape as the other overloads, but takes the invocation
     * flags already rendered by the caller instead of rendering them itself
     * from {@code (model, settings)} — the seam {@code CliStageExecutor}
     * (task 6.5) uses to pass {@link
     * AgentInvocationOptions#renderForExecutor(String, Map, java.nio.file.Path)}'s
     * output, which needs the round's decision-file path to bake in the
     * pinpoint {@code Write} allowance (FR12, D7) and therefore cannot be
     * produced from {@code (model, settings)} alone the way the other
     * overloads' internal {@link AgentInvocationOptions#render} call is.
     *
     * @param workspace the stage workspace whose root becomes the process's cwd
     * @param prompt the round's prompt text, passed via {@code -p}
     * @param factoryProperties resolved installation config: CLI binary path
     *     and env passthrough intent (D7)
     * @param invocationFlags the already-rendered {@code --model}/settings
     *     flags, inserted after the prompt and before the transport flags,
     *     verbatim; never null, may be empty
     * @param extraEnv variables merged into the child process's environment
     *     on top of the inherited default; never null, may be empty
     * @return the started process paired with its resolved command line, or
     *     {@code null} if the process failed to start
     */
    @Nullable
    public LaunchedAgentProcess launchWithFlags(
            DirectoryWorkspace workspace,
            String prompt,
            FactoryProperties factoryProperties,
            List<String> invocationFlags,
            Map<String, String> extraEnv) {
        List<String> command =
                AgentCommandLine.fromRenderedFlags(factoryProperties.agentCliBinary(), prompt, invocationFlags);
        return start(workspace, command, extraEnv);
    }

    @Nullable
    private LaunchedAgentProcess start(DirectoryWorkspace workspace, List<String> command) {
        return start(workspace, command, Map.of());
    }

    @Nullable
    private LaunchedAgentProcess start(
            DirectoryWorkspace workspace, List<String> command, Map<String, String> extraEnv) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspace.root().toFile());
        builder.environment().putAll(extraEnv);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return null;
        }
        return new LaunchedAgentProcess(process, command, clock.now());
    }
}
