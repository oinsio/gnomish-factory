package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.agent.command.CommandExecutorFactory;
import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.executorcontract.ExecutorName;
import java.util.Objects;

/**
 * The run's one {@link StageExecutor} (design D8 of define-executor-contract): each round goes to
 * what the stage's Mechanism section names — a {@code program} stage to the executor its {@code
 * executor.name} resolves to in the {@link ExecutorRegistry}, a {@code command} stage to the built-in
 * the registry holds under {@value CommandExecutorFactory#NAME}, every other stage to the agent arm,
 * the one type-keyed arm kept until {@code extract-agent-round-runner} makes the agent a named
 * executor. The engine keeps one port and never sees the choice (proposal G1).
 *
 * <p>The {@code COMMAND} arm is the one place a stage's type becomes a registry name (design D6 of
 * add-command-executor, the single-owner table): a {@code command} stage names no executor, so the arm
 * maps its type to the built-in's name and resolves it like any other; it holds no executor of its
 * own, and a {@code command} stage never reaches the agent arm.
 *
 * <p>The {@code api} type reaches the agent arm as it did before the dispatch existed; the law load
 * refuses an {@code api} stage ({@code ApiExecutorRule}), so no round of one is run. A stage that
 * names an executor is a {@code program} stage by the law load's rule ({@code ExecutorReferenceRule}),
 * so the name and the type cannot point at two executors.
 *
 * <p>Implements FR9, FR10 of define-executor-contract; FR8 of add-command-executor.
 */
final class ExecutorDispatch implements StageExecutor {

    private final ExecutorRegistry registry;
    private final StageExecutor agent;

    /**
     * @param registry the run's executors by name; never null
     * @param agent the {@code agent-cli} arm; never null
     */
    ExecutorDispatch(ExecutorRegistry registry, StageExecutor agent) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.agent = Objects.requireNonNull(agent, "agent");
    }

    @Override
    public ExecutionResult execute(Request request) {
        StageDefinition.Executor executor = request.stage().executor();
        return switch (executor.type()) {
            case PROGRAM ->
                registry.resolve(new ExecutorName(Objects.requireNonNull(
                                executor.name(), "a program stage names its executor (ExecutorReferenceRule)")))
                        .execute(request);
            case COMMAND ->
                registry.resolve(new ExecutorName(CommandExecutorFactory.NAME)).execute(request);
            case AGENT_CLI, API -> agent.execute(request);
        };
    }
}
