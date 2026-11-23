package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.agent.command.CommandExecutorFactory;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramStageExecutor;
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.executorcontract.ExecutorName;
import java.util.Objects;

/**
 * The run's one {@link StageExecutor} (design D8 of define-executor-contract): each round goes to the
 * {@link ExecutorRegistry} entry the stage's Mechanism section names — a {@code program} stage the
 * executor its {@code executor.name} resolves to, a {@code command} stage the built-in the registry
 * holds under {@value CommandExecutorFactory#NAME}, and every other stage the entry its executor block
 * resolves to ({@link StageDefinition.Executor#registryName()}): an agent block the reserved {@code
 * claude} entry (design D12 of extract-agent-round-runner, task 5.1a), whose stage half runs the agent
 * round runner. No arm runs an executor of its own: the type-keyed agent arm is gone, and every round
 * resolves an entry by name. The engine keeps one port and never sees the choice (proposal G1).
 *
 * <p>The {@code COMMAND} arm is the one place a {@code command} stage's type becomes a registry name
 * (design D6 of add-command-executor, the single-owner table): a {@code command} stage names no
 * executor, so the arm maps its type to the built-in's name and resolves it like any other; it holds
 * no executor of its own.
 *
 * <p>The {@code api} type resolves to the agent's entry as it reached the agent arm before
 * ({@code registryName}); the law load refuses an {@code api} stage ({@code ApiExecutorRule}), so no
 * round of one is run. A stage that names an executor is a {@code program} stage by the law load's
 * rule ({@code ExecutorReferenceRule}), so the name and the type cannot point at two executors.
 *
 * <p>The registry holds entries, not executors (design D18 of migrate-verify-to-executor-contract,
 * as amended 2026-11-10): each round resolves its entry and runs it through the shared round wrapper,
 * {@link ProgramStageExecutor}, over the run's one round source, which binds the entry's executor to
 * the round once it is open — so every round's token lands in the run's current-round cell alike
 * (FR14, FR21 of define-executor-contract).
 *
 * <p>Implements FR9, FR10 of define-executor-contract; FR8 of add-command-executor; FR13 of
 * migrate-verify-to-executor-contract; FR1, FR14 of extract-agent-round-runner.
 */
final class ExecutorDispatch implements StageExecutor {

    private final ExecutorRegistry registry;
    private final RoundEnvironmentSource rounds;

    /**
     * @param registry the run's executors by name; never null
     * @param rounds the run's one round source, which opens every round of every entry; never null
     */
    ExecutorDispatch(ExecutorRegistry registry, RoundEnvironmentSource rounds) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.rounds = Objects.requireNonNull(rounds, "rounds");
    }

    @Override
    public ExecutionResult execute(Request request) {
        StageDefinition.Executor executor = request.stage().executor();
        ExecutorName name =
                switch (executor.type()) {
                    case PROGRAM ->
                        new ExecutorName(Objects.requireNonNull(
                                executor.name(), "a program stage names its executor (ExecutorReferenceRule)"));
                    case COMMAND -> new ExecutorName(CommandExecutorFactory.NAME);
                    // Every other block resolves by the name its block resolves to, as a judge's does.
                    default -> new ExecutorName(executor.registryName());
                };
        return new ProgramStageExecutor(registry.resolve(name).rounds(), rounds).execute(request);
    }
}
