package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.agent.program.DescribedProgram;
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
 * The run's executors by name (design D8, D10 of define-executor-contract): the one place a stage's
 * {@code executor.name} is resolved to what runs its rounds. It holds one entry per executor the law
 * declares and the describe preflight described — a {@link ProgramStageExecutor} over the law's
 * declaration, the describe answer the preflight agreed with the law, and the credential names the
 * law grants it — each opening its rounds through the run's one round source, so a program round's
 * token lands in the run's current-round cell exactly as an agent round's does (FR14, FR21). No
 * built-in is registered by name in this change: {@code agent-cli} keeps its type-keyed arm in
 * {@link ExecutorDispatch} until {@code extract-agent-round-runner}, and {@code add-command-executor}
 * registers {@code command} here.
 *
 * <p>An entry is a stage executor, not a bare {@code Executor}: a {@code ProgramExecutor} is bound
 * to one round's execution environment (task 7.1), which exists only once the round is open, so the
 * value the registry holds at wiring is what builds that executor in each round. Built once at
 * wiring and immutable (design D12); the run's own thread reads it.
 *
 * <p>Implements FR9, FR10 of define-executor-contract.
 */
final class ExecutorRegistry {

    private final Map<ExecutorName, StageExecutor> byName;

    private ExecutorRegistry(Map<ExecutorName, StageExecutor> byName) {
        this.byName = Map.copyOf(byName);
    }

    /**
     * The registry of one run: every declaration of {@code definition} the preflight described.
     *
     * @param described what the describe preflight obtained for the run's law; never null
     * @param definition the law the run is frozen from; never null
     * @param law the run's frozen law, which each program round's request is built from; never null
     * @param rounds the run's one round source, the same the agent arm opens its rounds through
     * @param equipment the run's program equipment; never null
     * @return the registry; never null
     */
    static ExecutorRegistry of(
            DescribedLaw described,
            PipelineDefinition definition,
            PipelineLaw law,
            RoundEnvironmentSource rounds,
            ProgramRoundEquipment equipment) {
        Map<ExecutorName, StageExecutor> byName = new LinkedHashMap<>();
        for (ExecutorDeclaration declaration : definition.executors()) {
            String name = declaration.name();
            // A declaration the preflight did not describe has no answer only on a run that runs no
            // stage (RunDescribe's NoStageRuns medium): no round reaches the registry there.
            described
                    .answerOf(name)
                    .ifPresent(describe -> byName.put(
                            new ExecutorName(name),
                            new ProgramStageExecutor(
                                    law,
                                    new DescribedProgram(
                                            declaration,
                                            describe,
                                            described.executors().credentials().of(name)),
                                    rounds,
                                    equipment)));
        }
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
            throw new IllegalStateException(
                    "no executor named '" + name + "' is registered for this run; registered: " + byName.keySet());
        }
        return executor;
    }
}
