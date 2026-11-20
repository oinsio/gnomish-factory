package com.github.oinsio.gnomish.httpegress;

import java.util.List;

/**
 * The operator's {@code factory.egress.in-process} scope as the admission reads it (FR17, design D16
 * of migrate-verify-to-executor-contract): the hosts the factory process may reach beyond what each
 * connection grants, as entries of the one egress grammar ({@link EgressEntries}). Startup has
 * already judged every entry against the grammar, located at the file and line that set it.
 *
 * <p>Implements FR17 of migrate-verify-to-executor-contract.
 *
 * @param inProcess the scope's entries, as written; never null
 */
public record OperatorEgress(List<String> inProcess) {

    public OperatorEgress {
        inProcess = List.copyOf(inProcess);
    }

    /** The operator grants nothing: an empty scope, default-deny. */
    public static OperatorEgress none() {
        return new OperatorEgress(List.of());
    }
}
