package com.github.oinsio.gnomish.app.describe;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.sandbox.ProgramCredentialGrant;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The describe preflight (design D5 of define-executor-contract, phase b): once the task's
 * execution environment is live and before the engine starts, {@code describe} runs once per
 * declared {@code program} executor through that environment, and the answers are checked against
 * the law. Every failure is the task's (ADR 0019, decision 5): a program that cannot answer —
 * a configuration error, or an external failure when the environment refused to start it or the
 * bound killed it — and an answer that disagrees with the law are each a located error of {@link
 * DescribeOutcome.Invalid}, which parks the task as an invalid law does; the preflight's own
 * refusal never leaves this class. The engine never sees either as a round.
 *
 * <p>One instance serves one run, whose law is one law commit, and caches each answer by the
 * program's declared argv: {@code describe} takes no request, so two executors one law declares over
 * the same program — differing in settings, credentials or egress — are described by one process,
 * each under its own name. The cache is the run's own thread's (design D12: no lock, nothing held
 * across a child).
 * The preflight writes nothing durable (design D11).
 *
 * <p>Implements FR4, FR13, NFR-S1, UX1 of define-executor-contract.
 */
public final class DescribePreflight {

    private final ProgramDescribe program;
    private final ProgramCredentialGrant grant;
    private final Map<List<String>, Describe> cache = new HashMap<>();

    /**
     * @param clock the read-time source of the bounded wait; never null
     * @param bounds the run's program bounds; one describe may take {@link ProgramBounds#startup()}
     *     (NFR-R2); never null
     * @param grant the run's program credential grant ({@code ChildEnvAllowlist#programGrant}), which
     *     judges every credential the law grants against the run's shared child-environment layers;
     *     never null
     */
    public DescribePreflight(InstantSource clock, ProgramBounds bounds, ProgramCredentialGrant grant) {
        this.program = new ProgramDescribe(clock, bounds.startup());
        this.grant = grant;
    }

    /**
     * Describes every {@code program} executor {@code definition} declares, in {@code environment}.
     *
     * @param environment the task's live execution environment; never null
     * @param definition the law the task runs under; never null
     * @return the answers obtained, or every located error; never null
     */
    public DescribeOutcome describe(TaskExecutionEnvironment environment, PipelineDefinition definition) {
        Map<String, Describe> answers = new LinkedHashMap<>();
        List<ConfigError> errors = new ArrayList<>();
        // Every declaration is a program today (ExecutorDeclaration.Type has the one constant); a
        // built-in declared by name, when one exists, answers describe in process and needs no spawn.
        for (ExecutorDeclaration declaration : definition.executors()) {
            try {
                answers.put(
                        declaration.name(),
                        cache.computeIfAbsent(
                                declaration.program(), ignored -> program.describe(environment, declaration)));
            } catch (ExecutorDescribeRefusedException refused) {
                errors.add(refused.error());
            }
        }
        errors.addAll(DescribeAgreement.errors(definition, answers, grant));
        if (!errors.isEmpty()) {
            return new DescribeOutcome.Invalid(errors);
        }
        return new DescribeOutcome.Described(answers);
    }
}
