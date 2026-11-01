package com.github.oinsio.gnomish.domain.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The credential variable names each described {@code program} executor is granted, keyed by the
 * executor's law name (NFR-S1 of define-executor-contract, design D10): what the executor's describe
 * declares, which the describe preflight has shown equal to what the law's {@code
 * executors.<name>.credentials} grants. Names only, never a value: the program's child receives
 * them as its own resolved layer, each value resolved through the child-environment value source
 * ({@code ChildEnvAllowlist#programGrant}, ADR 0017) and added to that one exec's factory-set variables —
 * never declared on the run's child-environment allowlist, whose declared set is the names every
 * child is denied.
 *
 * <p>One producer, the describe preflight; an executor with no describe (an {@code agent-cli}
 * stage) has no entry. Inert value data compared by content; the map and its lists are copied and
 * unmodifiable.
 *
 * <p>Implements NFR-S1 of define-executor-contract.
 *
 * @param byExecutor the granted credential names per executor name; never null, possibly empty
 */
public record ExecutorCredentialNames(Map<String, List<String>> byExecutor) {

    private static final ExecutorCredentialNames NONE = new ExecutorCredentialNames(Map.of());

    public ExecutorCredentialNames {
        byExecutor = copied(byExecutor);
    }

    /** No executor described, so no credential granted. */
    public static ExecutorCredentialNames none() {
        return NONE;
    }

    /**
     * The names granted to {@code executor}.
     *
     * @param executor the executor's law name; never null
     * @return its granted names in describe order; empty when it has none or was not described
     */
    public List<String> of(String executor) {
        return byExecutor.getOrDefault(executor, List.of());
    }

    /**
     * Copies the map and each list. Explicit static method so PIT's record-constructor filter does
     * not exempt the copy from the mutation gate.
     */
    private static Map<String, List<String>> copied(Map<String, List<String>> byExecutor) {
        var copy = new LinkedHashMap<String, List<String>>();
        byExecutor.forEach((name, names) -> copy.put(name, List.copyOf(names)));
        return Collections.unmodifiableMap(copy);
    }
}
