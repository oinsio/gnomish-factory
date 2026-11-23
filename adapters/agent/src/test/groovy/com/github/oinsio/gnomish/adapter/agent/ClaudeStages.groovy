package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.claude.ClaudeDialect
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.agent.program.ProgramStageExecutor
import com.github.oinsio.gnomish.adapter.agent.runner.AgentRunners
import com.github.oinsio.gnomish.adapter.agent.runner.AgentStageRounds
import com.github.oinsio.gnomish.adapter.agent.runner.StdoutCaps
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * The agent stage executor a run wires since task 5.1a of extract-agent-round-runner, for the specs of
 * this module that drive a real CLI round ({@code testing.md}, "Fixtures assemble through production
 * owners"): the stage half of the {@code claude} entry — {@link AgentStageRounds} over {@link
 * AgentRunners} with the Claude dialect over the configured binary — run through the shared round
 * wrapper, {@link ProgramStageExecutor}, over the host round source, as {@code ExecutorDispatch} runs an
 * agent stage. The bootstrap twin, {@code ClaudeStageFixture}, builds the same over {@code ClaudeEntry}.
 */
final class ClaudeStages {

    private ClaudeStages() {}

    /**
     * @param agent the installation's properties: the binary the dialect launches
     * @param clock the round's virtual clock
     * @param law the run's frozen law, the source of the round prompt
     * @param listener the run's progress listener
     * @param childEnv the run's child-environment allowlist
     * @param rounds the round source; the host source over {@code clock} and {@code childEnv} by default
     */
    static StageExecutor executor(FactoryProperties agent, VirtualClock clock, PipelineLaw law,
            AgentProgressListener listener = { event -> } as AgentProgressListener,
            ChildEnvAllowlist childEnv = ChildEnvAllowlist.none(),
            RoundEnvironmentSource rounds = new HostRoundEnvironmentSource(clock, childEnv)) {
        def equipment = new ProgramRoundEquipment(ProgramBounds.DEFAULTS, VirtualTimeEquipment.on(clock),
                childEnv.programGrant(), RoundScratchDirectory.HOST_TEMP, LastRegisteredCommit.NONE)
        def dialect = ClaudeDialect.of(agent.agentCliBinary(), UntrustedText.factory('built-in'), childEnv)
        def runners = new AgentRunners<>('claude', dialect, listener, equipment, StdoutCaps.DEFAULTS)
        new ProgramStageExecutor(new AgentStageRounds(runners, law), rounds)
    }

    /** The answer instruction the Claude dialect declares for a {@code transform} round's prompt (FR19). */
    static String answerInstruction() {
        ClaudeDialect.of('claude', UntrustedText.factory('built-in'), ChildEnvAllowlist.none())
                .answerForm(Role.TRANSFORM).instruction()
    }
}
