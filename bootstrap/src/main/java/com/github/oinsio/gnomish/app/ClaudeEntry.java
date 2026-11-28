package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.agent.LoggingAgentProgressListener;
import com.github.oinsio.gnomish.adapter.agent.claude.ClaudeDialect;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment;
import com.github.oinsio.gnomish.adapter.agent.runner.AgentCheckBinding;
import com.github.oinsio.gnomish.adapter.agent.runner.AgentRunners;
import com.github.oinsio.gnomish.adapter.agent.runner.StdoutCaps;
import com.github.oinsio.gnomish.adapter.law.PipelineLaw;
import com.github.oinsio.gnomish.app.port.check.FreshBoxSource;
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.subprocess.ProcessLaunch;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import org.jspecify.annotations.Nullable;

/**
 * The run's {@code claude} registry entry (FR14 of extract-agent-round-runner, design D12): the
 * {@link ClaudeDialect} over the installation's agent CLI binary and the run's allowlist, run by the
 * agent round runner with the shared progress renderer and the factory's stdout caps; its stage half
 * over each round's environment, and its check half over a fresh box per request in a run that has
 * boxes, or the host environment in a run that has none (design D2, the judge's one exemption). This is
 * the one production construction of the runner ({@code ExecutorRegistryOwnerSpec}).
 *
 * <p>The dialect's describe names its version {@link BuiltInExecutors#VERSION}, as every built-in's
 * describe does: the entry is compiled into the factory and the CLI's own version is not probed in
 * process. The version is shown on the entry's records ({@code claude@built-in}) and never pinned: the
 * task's executor versions hold described programs only (design D12 of extract-agent-round-runner,
 * "The version of a built-in"). {@code
 * externalize-claude-executor} (proposal NG1) turns the entry into a described program, whose describe
 * reports the CLI's version.
 *
 * <p>Implements FR14 of extract-agent-round-runner.
 */
final class ClaudeEntry {

    /** The version the in-process dialect describes, as every built-in's describe does. */
    static final UntrustedText VERSION = UntrustedText.factory(BuiltInExecutors.VERSION);

    private ClaudeEntry() {}

    /**
     * The entry of one run.
     *
     * @param factoryProperties the installation's configuration: the agent CLI binary; never null
     * @param childEnv the run's child-environment allowlist: the dialect's egress host and credentials,
     *     and every host child's environment; never null
     * @param law the run's frozen law, the source of each stage round's prompt; never null
     * @param equipment the run's program equipment; never null
     * @param freshBoxes the run's fresh boxes; {@code null} for a host run
     * @return the entry named {@link BuiltInExecutors#CLAUDE}; never null
     * @throws IllegalArgumentException when the configured base URL names no host
     */
    static RegistryEntry of(
            FactoryProperties factoryProperties,
            ChildEnvAllowlist childEnv,
            PipelineLaw law,
            ProgramRoundEquipment equipment,
            @Nullable FreshBoxSource freshBoxes) {
        var runners = new AgentRunners<>(
                BuiltInExecutors.CLAUDE,
                ClaudeDialect.of(factoryProperties.agentCliBinary(), VERSION, childEnv),
                new LoggingAgentProgressListener(),
                equipment,
                StdoutCaps.DEFAULTS);
        var checks = freshBoxes != null
                ? AgentCheckBinding.inFreshBoxes(runners, freshBoxes)
                : AgentCheckBinding.onHost(runners, childEnv, ProcessLaunch.grouped());
        return RegistryEntry.agent(runners, law, checks);
    }
}
