package com.github.oinsio.gnomish.domain.pipeline;

import java.util.List;
import java.util.Map;

/**
 * One entry of the law's {@code executors:} section in {@code config.yaml} (the glossary's
 * <em>executor declaration</em>): a name mapped to its type, the argv of a {@code program}, its
 * opaque settings, the credential variable names the law grants it and the egress hosts it may
 * reach. A stage references it by {@code executor.name}. Declarations come from the law commit
 * only, so they are law content, pinned with the rest of {@code .gnomish/} by the law commit a
 * task is created at.
 *
 * <p>Inert, immutable data. The structural rules — the name grammar, a known type, a non-empty
 * argv, credential and egress grammars — were judged at load by the adapter, which reports a
 * located error for each; {@code settings} are judged later, against the schema the executor's
 * describe declares (design D5 of define-executor-contract, phase b).
 *
 * <p>Implements FR9, NFR-S3 of define-executor-contract.
 *
 * @param name the declaration's name, the key a stage's {@code executor.name} references; never
 *     null
 * @param type the declaration's type; never null
 * @param program the argv of a {@code program} declaration, the program first; immutable
 * @param settings opaque executor settings as plain JDK types; immutable, possibly empty
 * @param credentials the environment variable names the law grants the executor; immutable,
 *     possibly empty
 * @param egress the hosts the executor may reach; immutable, possibly empty
 */
public record ExecutorDeclaration(
        String name,
        Type type,
        List<String> program,
        Map<String, Object> settings,
        List<String> credentials,
        List<String> egress) {

    public ExecutorDeclaration {
        program = List.copyOf(program);
        settings = Map.copyOf(settings);
        credentials = List.copyOf(credentials);
        egress = List.copyOf(egress);
    }

    /**
     * The kind of a declaration. {@code program} is the only declarable type: a built-in executor
     * is not declared in the law. The {@code command} built-in (design D6 of add-command-executor)
     * is registered at wiring under its own name, and a {@code command} stage reaches it by its
     * stage type, carrying its command on the stage, so no declaration names it.
     */
    public enum Type {
        /** A program the task's execution environment runs through the executor contract. */
        PROGRAM
    }
}
