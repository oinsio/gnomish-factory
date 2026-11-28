package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.agent.runner.AgentCheckBinding
import com.github.oinsio.gnomish.adapter.agent.runner.AgentRunners
import com.github.oinsio.gnomish.adapter.agent.runner.FakeDialect
import com.github.oinsio.gnomish.adapter.agent.runner.StdoutCaps
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.subprocess.ProcessLaunch

/**
 * The agent entry for a spec that builds an {@link ExecutorRegistry} by hand: the reserved {@code
 * claude} name over the fake dialect, its check half on the host, launching nothing of its own. Its
 * stage half prompts from {@code law}, so a spec that dispatches an agent stage hands the run's law and
 * runs the fake dialect's CLI in the environment its round source opens (task 5.1a of
 * extract-agent-round-runner). A spec that drives the entry otherwise builds it through {@link
 * ClaudeEntry} or {@link RegistryEntry#agent} itself.
 */
final class AgentEntryFixture {

    private AgentEntryFixture() {}

    static RegistryEntry idle(ProgramRoundEquipment equipment, PipelineLaw law = PipelineLaw.ofContent([:])) {
        def runners = new AgentRunners<>(BuiltInExecutors.CLAUDE, new FakeDialect(), { event -> } as AgentProgressListener,
        equipment, StdoutCaps.DEFAULTS)
        RegistryEntry.agent(runners, law,
                AgentCheckBinding.onHost(runners, ChildEnvAllowlist.none(), { builder, stdin ->
                    throw new IllegalStateException('the idle agent entry launches nothing')
                } as ProcessLaunch))
    }
}
