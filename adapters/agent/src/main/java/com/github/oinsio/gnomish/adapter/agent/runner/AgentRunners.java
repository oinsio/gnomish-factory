package com.github.oinsio.gnomish.adapter.agent.runner;

import com.github.oinsio.gnomish.adapter.agent.CompositeAgentProgressListener;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment;
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.util.List;

/**
 * The one construction of an agent registry entry's {@link AgentRoundRunner} (FR14 of
 * extract-agent-round-runner, design D12): the entry's name, its dialect, the run's progress listener,
 * the run's program equipment and the stdout caps, held for the run, and a runner bound to whichever
 * environment a half of the entry binds it to — a stage round's environment ({@link
 * AgentStageRounds}), or a check request's fresh box or host environment ({@link AgentCheckBinding}).
 * The describe of every runner it builds is the dialect's, so the entry's describe and its bound
 * runners' cannot differ.
 *
 * <p>Built once per run at run assembly and immutable; it keeps nothing it bound.
 *
 * <p>Implements FR14 of extract-agent-round-runner.
 *
 * @param <E> the dialect's own event type
 */
public final class AgentRunners<E> {

    private final String name;
    private final AgentDialect<E> dialect;
    private final AgentProgressListener listener;
    private final ProgramRoundEquipment equipment;
    private final StdoutCaps caps;

    /**
     * @param name the entry's name, as the round's result names the executor; never blank
     * @param dialect the vendor's dialect; never null
     * @param listener the run's progress listener for every round; never null
     * @param equipment the run's bounds, time, credential grant, stderr parent and round cell; never null
     * @param caps the rounds' stdout caps; never null
     */
    public AgentRunners(
            String name,
            AgentDialect<E> dialect,
            AgentProgressListener listener,
            ProgramRoundEquipment equipment,
            StdoutCaps caps) {
        this.name = name;
        this.dialect = dialect;
        this.listener = listener;
        this.equipment = equipment;
        this.caps = caps;
    }

    /** The entry's name; never blank. */
    public String name() {
        return name;
    }

    /** The dialect's describe answer, the entry's for every binding; never null. */
    public Describe describe() {
        return dialect.describe();
    }

    /** The run's program equipment, which a request's hints and a host environment's clock read. */
    ProgramRoundEquipment equipment() {
        return equipment;
    }

    /**
     * A runner bound to {@code environment}; its caller puts it behind the guard probe's owner.
     *
     * @param environment the materialized environment its rounds run in; never null
     * @return the runner; never null
     */
    public AgentRoundRunner<E> runner(TaskExecutionEnvironment environment) {
        return new AgentRoundRunner<>(name, dialect, environment, equipment, listener, caps);
    }

    /**
     * A runner bound to {@code environment} whose rounds also report to {@code roundListener} — the
     * open stage round's own listener (the mid-round harvest of a box round, the mid-round push of a
     * git-mode host round), joined to the run's; its caller puts it behind the guard probe's owner.
     *
     * @param environment the materialized environment its rounds run in; never null
     * @param roundListener the open round's listener; never null
     * @return the runner; never null
     */
    public AgentRoundRunner<E> runner(TaskExecutionEnvironment environment, AgentProgressListener roundListener) {
        return new AgentRoundRunner<>(
                name,
                dialect,
                environment,
                equipment,
                new CompositeAgentProgressListener(List.of(listener, roundListener)),
                caps);
    }
}
