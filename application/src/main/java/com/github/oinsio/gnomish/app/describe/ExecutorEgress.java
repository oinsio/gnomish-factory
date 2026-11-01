package com.github.oinsio.gnomish.app.describe;

import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.sandbox.EgressEntries;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The egress-host union of a law's {@code program} executors (design D10 of
 * define-executor-contract): every host an {@code executors.<name>.egress} entry grants, in the one
 * spelling the guard matches ({@link EgressEntries#normalized}), each once, in declaration order —
 * what a run's box guard admits beside the operator's {@code factory.sandbox.egress-allowlist}. It is
 * the law's grant, not the describe's: the box and its guard exist before {@code describe} can run
 * in it, and the describe preflight then refuses every granted host the describe does not declare
 * ({@link GrantAgreement}) before the first round. A host the describe declares and the law does not
 * grant is never admitted. Pure.
 *
 * <p>Implements FR4, FR10 of define-executor-contract.
 */
public final class ExecutorEgress {

    private ExecutorEgress() {}

    /**
     * The union of {@code definition}'s executor egress hosts.
     *
     * @param definition the law; never null
     * @return the normalized hosts, each once; immutable, empty when no executor grants egress
     */
    public static List<String> union(PipelineDefinition definition) {
        Set<String> hosts = new LinkedHashSet<>();
        for (ExecutorDeclaration declaration : definition.executors()) {
            declaration.egress().forEach(host -> hosts.add(EgressEntries.normalized(host)));
        }
        return List.copyOf(hosts);
    }
}
