package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.agent.program.DescribedProgram;
import com.github.oinsio.gnomish.adapter.agent.program.PendingRuns;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramCheckBinding;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramExecutorFactory;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment;
import com.github.oinsio.gnomish.adapter.law.PipelineLaw;
import com.github.oinsio.gnomish.app.port.check.FreshBoxSource;
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.executorcontract.ExecutorName;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The run's executors by name (design D8, D10 of define-executor-contract; design D6 of
 * add-command-executor; design D18 of migrate-verify-to-executor-contract, as amended 2026-11-10):
 * the one place a stage's or a check's executor name is resolved, for stage rounds and check rounds
 * alike. Its value is a {@link RegistryEntry} per name — the name, its describe answer and its
 * long-lived collaborators — never a ready executor: a contract executor is bound to one round's
 * execution environment, or to one check and its working copy, values that exist only at
 * resolution. It holds:
 *
 * <ul>
 *   <li>one stage entry per executor the law declares and the describe preflight described — a {@link
 *       ProgramExecutorFactory} of the law's declaration, the describe answer the preflight agreed
 *       with the law, the credential names the law grants it, and the run's one {@link PendingRuns}
 *       of the program, built here with the entry and shared by every binding of it (design D14 as
 *       amended 2026-11-13) — its stage rounds and, in a run with fresh boxes, its check rounds,
 *       each request in a box of its own ({@link ProgramCheckBinding}, task 5.3);
 *   <li>the built-ins' entries ({@link BuiltInEntries}): the {@code command} stage built-in, and each
 *       verdict built-in the law's checks name.
 * </ul>
 *
 * <p>A law that declares an executor under one of the six {@link BuiltInExecutors} names is refused
 * at load; the refusal here, over the same set, is the safety net behind that load error, so neither
 * entry silently replaces the other. {@code agent-cli} is not an entry: it keeps its type-keyed arm in
 * {@link ExecutorDispatch} until {@code extract-agent-round-runner}.
 *
 * <p>Built once per run at run assembly and immutable (design D12; {@code process-invariants.md}):
 * nothing bound is cached here, so a bound executor lives for its round or its check. The run's own
 * thread reads it.
 *
 * <p>Implements FR9, FR10 of define-executor-contract; FR8 of add-command-executor; FR1, FR3, FR13 of
 * migrate-verify-to-executor-contract.
 */
final class ExecutorRegistry {

    private final Map<ExecutorName, RegistryEntry> entries;

    private ExecutorRegistry(Map<ExecutorName, RegistryEntry> entries) {
        this.entries = Map.copyOf(entries);
    }

    /**
     * The registry of one run: every declaration of {@code definition} the preflight described, and
     * the built-ins' entries.
     *
     * @param described what the describe preflight obtained for the run's law; never null
     * @param definition the law the run is frozen from; never null
     * @param law the run's frozen law, which each program round's request is built from; never null
     * @param equipment the run's program equipment; never null
     * @param builtIns the run's built-ins, handed in typed by the selector; never null
     * @param freshBoxes the run's fresh boxes, which a program's check binds through; {@code null} for
     *     a host run, whose program entries bind no check (proposal NG14)
     * @return the registry; never null
     * @throws IllegalStateException when the law declares an executor under a built-in's name
     */
    static ExecutorRegistry of(
            DescribedLaw described,
            PipelineDefinition definition,
            PipelineLaw law,
            ProgramRoundEquipment equipment,
            BuiltInEntries builtIns,
            @Nullable FreshBoxSource freshBoxes) {
        Map<ExecutorName, RegistryEntry> byName = new LinkedHashMap<>();
        for (ExecutorDeclaration declaration : definition.executors()) {
            String name = declaration.name();
            if (BuiltInExecutors.isBuiltIn(name)) {
                throw new IllegalStateException(
                        "the law declares an executor named '" + name + "', a built-in's name; rename the declaration");
            }
            // A declaration the preflight did not describe has no answer only on a run that runs no
            // stage (RunDescribe's NoStageRuns medium): no round reaches the registry there.
            described.answerOf(name).ifPresent(describe -> {
                var program = new DescribedProgram(
                        declaration,
                        describe,
                        described.executors().credentials().of(name));
                // One table per program per run, shared by its stage and check bindings (D14).
                var runs = new PendingRuns();
                var checks = freshBoxes == null ? null : new ProgramCheckBinding(program, equipment, runs, freshBoxes);
                byName.put(
                        new ExecutorName(name),
                        RegistryEntry.program(
                                describe, new ProgramExecutorFactory(law, program, equipment, runs), checks));
            });
        }
        for (RegistryEntry entry : builtIns.of(definition, described)) {
            byName.put(entry.name(), entry);
        }
        return new ExecutorRegistry(byName);
    }

    /**
     * The entry of the executor named {@code name}, bound by its caller at resolution.
     *
     * @param name a stage's {@code executor.name}, a built-in's name, or a check's executor; never null
     * @return the registered entry; never null
     * @throws IllegalStateException when the run registered no executor of that name — the law load
     *     refuses an unknown name and the preflight describes every declaration before a round, so
     *     this is a wiring defect, which the engine records as the round's failure
     */
    RegistryEntry resolve(ExecutorName name) {
        RegistryEntry entry = entries.get(name);
        if (entry == null) {
            throw new IllegalStateException("no executor named '" + name + "' is registered for this run; registered: "
                    + entries.keySet().stream()
                            .map(ExecutorName::value)
                            .sorted()
                            .toList());
        }
        return entry;
    }
}
