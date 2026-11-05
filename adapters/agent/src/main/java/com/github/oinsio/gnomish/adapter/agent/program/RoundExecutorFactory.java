package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;

/**
 * What one registry entry runs in each round of the shared round wrapper (design D2 of
 * add-command-executor): the round environment in, the contract {@link Executor} out. The wrapper,
 * {@link ProgramStageExecutor}, owns the round — open, guard probes, close, denials and the one
 * mapping to the engine's result — and asks this factory only for what differs between a law-declared
 * {@code program} ({@link ProgramExecutorFactory}) and a built-in: the executor's name, the request a
 * round sends, and the executor bound to the round's environment.
 *
 * <p>Implements FR4, FR8 of add-command-executor.
 */
public interface RoundExecutorFactory {

    /**
     * The executor's name, as the round's result and its operator lines name it.
     *
     * @return the name; never blank
     */
    String name();

    /**
     * The request one round sends, built before the round opens: a request that cannot be built fails
     * the round before any environment is touched.
     *
     * @param round the engine's round inputs; never null
     * @return the request; never null
     */
    ExecutorRequest request(StageExecutor.Request round);

    /**
     * The executor of one round, bound to the round's environment.
     *
     * @param environment the open round's execution environment; never null
     * @return the executor; never null
     */
    Executor executor(TaskExecutionEnvironment environment);
}
