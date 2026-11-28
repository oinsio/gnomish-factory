package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;

/**
 * What one registry entry runs in each round of the shared round wrapper (design D2 of
 * add-command-executor): the round environment in, the contract {@link Executor} out. The wrapper,
 * {@link ProgramStageExecutor}, owns the round — open, close, denials and the one mapping to the
 * engine's result — and puts the executor this factory binds behind {@link GuardedExecutor}, the one
 * owner of the guard probe (design D13 of extract-agent-round-runner); it asks this factory only for what differs between a law-declared
 * {@code program} ({@link ProgramExecutorFactory}), a built-in and the agent entry: the executor's
 * name and the noun its refusals name it by, the request a round sends, what the open round adds to
 * that request, and the executor bound to the open round.
 *
 * <p>A {@code program} and a built-in are bound to the round's environment alone, and their request is
 * complete before the round opens: the defaults. The agent entry's stage half (task 5.1a of
 * extract-agent-round-runner) also takes the open round's answer file into its request's policy and
 * the round's own progress listener into its runner — the two facts only an open round knows.
 *
 * <p>Implements FR4, FR8 of add-command-executor; FR14 of extract-agent-round-runner.
 */
public interface RoundExecutorFactory {

    /**
     * The executor's name, as the round's result and its operator lines name it.
     *
     * @return the name; never blank
     */
    String name();

    /**
     * What kind of executor the wrapper's own refusals name — a dead guard, a round whose mechanics
     * failed: a {@code program} executor unless the factory says otherwise.
     *
     * @return the noun's subject; never null
     */
    default ProgramFailures.Subject subject() {
        return ProgramFailures.Subject.PROGRAM;
    }

    /**
     * The request one round sends, built before the round opens: a request that cannot be built fails
     * the round before any environment is touched.
     *
     * @param round the engine's round inputs; never null
     * @return the request; never null
     */
    ExecutorRequest request(StageExecutor.Request round);

    /**
     * The request {@link #request} built, completed by the open round; unchanged unless the factory
     * says otherwise.
     *
     * @param request the request built before the round opened; never null
     * @param round the open round; never null
     * @return the request the round sends; never null
     */
    default ExecutorRequest opened(ExecutorRequest request, RoundEnvironmentSource.Round round) {
        return request;
    }

    /**
     * The executor of one round, bound to the round's environment.
     *
     * @param environment the open round's execution environment; never null
     * @return the executor; never null
     */
    Executor executor(TaskExecutionEnvironment environment);

    /**
     * The executor of one open round. Its one caller is the round wrapper, which puts it behind {@link
     * GuardedExecutor} before any request ({@code GuardProbeOwnerSpec}); bound to the round's
     * environment ({@link #executor(TaskExecutionEnvironment)}) unless the factory says otherwise.
     *
     * @param round the open round; never null
     * @return the executor; never null
     */
    default Executor executor(RoundEnvironmentSource.Round round) {
        return executor(round.environment());
    }
}
