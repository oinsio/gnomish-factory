package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration;
import com.github.oinsio.gnomish.executorcontract.Describe;
import java.util.List;
import java.util.Objects;

/**
 * One law-declared {@code program} executor as the describe preflight left it (FR4, FR13, NFR-S1 of
 * define-executor-contract): the law's declaration, the describe answer the preflight obtained and
 * agreed with the law, and the credential names the law grants it, which the preflight showed equal
 * to the describe's. What a {@link ProgramExecutor} is built from in each round's environment.
 *
 * @param declaration the law's declaration; never null
 * @param describe the describe answer the preflight obtained; never null
 * @param credentials the granted credential names; immutable, possibly empty
 */
public record DescribedProgram(ExecutorDeclaration declaration, Describe describe, List<String> credentials) {

    /** Refuses a missing member. */
    public DescribedProgram {
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(describe, "describe");
        credentials = List.copyOf(credentials);
    }
}
