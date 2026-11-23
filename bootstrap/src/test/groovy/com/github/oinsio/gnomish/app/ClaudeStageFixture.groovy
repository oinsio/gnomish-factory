package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.RoundScratchDirectory
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.agent.program.ProgramStageExecutor
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist

/**
 * The agent stage executor a run wires since task 5.1a of extract-agent-round-runner, for a fixture
 * that assembles a run's round adapters by hand ({@code testing.md}, "Fixtures assemble through
 * production owners"): the stage half of the {@code claude} entry {@link ClaudeEntry} composes, run
 * through the shared round wrapper over the fixture's round source, exactly as {@link
 * ExecutorDispatch} runs an agent stage — so a hand-assembled flow runs the agent round runner with
 * the Claude dialect over the configured agent binary, not a path production no longer takes.
 */
final class ClaudeStageFixture {

    private ClaudeStageFixture() {}

    /**
     * @param agent the installation's properties: the agent binary the dialect launches
     * @param clock the run's virtual clock, which the round's wait and wall time read
     * @param childEnv the run's child-environment allowlist
     * @param law the run's frozen law, the source of each round's prompt
     * @param rounds the fixture's round source
     * @param registered the run's round cell, or none for an in-place run
     * @return the agent stage executor; never null
     */
    static StageExecutor executor(FactoryProperties agent, VirtualClock clock, ChildEnvAllowlist childEnv, PipelineLaw law,
            RoundEnvironmentSource rounds, LastRegisteredCommit registered = LastRegisteredCommit.NONE) {
        def equipment = new ProgramRoundEquipment(ProgramBounds.DEFAULTS, VirtualTimeEquipment.on(clock),
                childEnv.programGrant(), RoundScratchDirectory.HOST_TEMP, registered)
        new ProgramStageExecutor(ClaudeEntry.of(agent, childEnv, law, equipment, null).rounds(), rounds)
    }
}
