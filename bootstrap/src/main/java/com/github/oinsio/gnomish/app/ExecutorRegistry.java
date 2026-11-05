package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.agent.command.CommandExecutorFactory;
import com.github.oinsio.gnomish.adapter.agent.program.DescribedProgram;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramExecutorFactory;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramStageExecutor;
import com.github.oinsio.gnomish.adapter.law.PipelineLaw;
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.executorcontract.ExecutorName;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The run's executors by name (design D8, D10 of define-executor-contract; design D6 of
 * add-command-executor): the one place a stage's executor name is resolved to what runs its rounds.
 * Every entry is the shared round wrapper, {@link ProgramStageExecutor}, over a round-executor factory,
 * each opening its rounds through the run's one round source, so a registry round's token lands in the
 * run's current-round cell exactly as an agent round's does (FR14, FR21). It holds:
 *
 * <ul>
 *   <li>one entry per executor the law declares and the describe preflight described — a {@link
 *       ProgramExecutorFactory} of the law's declaration, the describe answer the preflight agreed
 *       with the law, and the credential names the law grants it;
 *   <li>the {@code command} built-in under its name {@value CommandExecutorFactory#NAME}, a {@link
 *       CommandExecutorFactory} the wiring hands in beside the declarations. A {@code command} stage
 *       names no executor; the dispatch maps its type to this name.
 * </ul>
 *
 * <p>A law that declares an executor under the built-in's name is refused when the registry is built,
 * so neither entry silently replaces the other. {@code agent-cli} is not an entry: it keeps its
 * type-keyed arm in {@link ExecutorDispatch} until {@code extract-agent-round-runner}.
 *
 * <p>An entry is a stage executor, not a bare {@code Executor}: a contract executor is bound to one
 * round's execution environment (task 7.1), which exists only once the round is open, so the value
 * the registry holds at wiring is what builds that executor in each round. Built once at wiring and
 * immutable (design D12); the run's own thread reads it.
 *
 * <p>Implements FR9, FR10 of define-executor-contract; FR8 of add-command-executor.
 */
final class ExecutorRegistry {

    private final Map<ExecutorName, StageExecutor> byName;

    private ExecutorRegistry(Map<ExecutorName, StageExecutor> byName) {
        this.byName = Map.copyOf(byName);
    }

    /**
     * The registry of one run: every declaration of {@code definition} the preflight described, and
     * the {@code command} built-in.
     *
     * @param described what the describe preflight obtained for the run's law; never null
     * @param definition the law the run is frozen from; never null
     * @param law the run's frozen law, which each program round's request is built from; never null
     * @param rounds the run's one round source, the same the agent arm opens its rounds through
     * @param equipment the run's program equipment; never null
     * @param command the {@code command} built-in's factory, registered under its name; never null
     * @return the registry; never null
     * @throws IllegalStateException when the law declares an executor under the built-in's name
     */
    static ExecutorRegistry of(
            DescribedLaw described,
            PipelineDefinition definition,
            PipelineLaw law,
            RoundEnvironmentSource rounds,
            ProgramRoundEquipment equipment,
            CommandExecutorFactory command) {
        Map<ExecutorName, StageExecutor> byName = new LinkedHashMap<>();
        for (ExecutorDeclaration declaration : definition.executors()) {
            String name = declaration.name();
            if (name.equals(command.name())) {
                throw new IllegalStateException("the law declares an executor named '" + name
                        + "', the name of the built-in a command stage runs; rename the declaration");
            }
            // A declaration the preflight did not describe has no answer only on a run that runs no
            // stage (RunDescribe's NoStageRuns medium): no round reaches the registry there.
            described
                    .answerOf(name)
                    .ifPresent(describe -> byName.put(
                            new ExecutorName(name),
                            new ProgramStageExecutor(
                                    new ProgramExecutorFactory(
                                            law,
                                            new DescribedProgram(
                                                    declaration,
                                                    describe,
                                                    described
                                                            .executors()
                                                            .credentials()
                                                            .of(name)),
                                            equipment),
                                    rounds)));
        }
        byName.put(new ExecutorName(command.name()), new ProgramStageExecutor(command, rounds));
        return new ExecutorRegistry(byName);
    }

    /**
     * What runs the rounds of a stage that names {@code name}.
     *
     * @param name the stage's {@code executor.name}; never null
     * @return the registered stage executor; never null
     * @throws IllegalStateException when the run registered no executor of that name — the law load
     *     refuses an unknown name and the preflight describes every declaration before a round, so
     *     this is a wiring defect, which the engine records as the round's failure
     */
    StageExecutor resolve(ExecutorName name) {
        StageExecutor executor = byName.get(name);
        if (executor == null) {
            throw new IllegalStateException("no executor named '" + name + "' is registered for this run; registered: "
                    + byName.keySet().stream().map(ExecutorName::value).sorted().toList());
        }
        return executor;
    }
}
