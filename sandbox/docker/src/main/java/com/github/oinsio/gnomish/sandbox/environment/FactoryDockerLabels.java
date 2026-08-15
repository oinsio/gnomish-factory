package com.github.oinsio.gnomish.sandbox.environment;

import java.util.Optional;

/**
 * The factory-owned labels and object names stamped on every Docker object the
 * container adapter creates (design D2, FR3, FR11): a network, a volume, and a
 * container per task. Every object carries {@link #FACTORY_LABEL}={@code true} so
 * the startup orphan sweep ({@code ContainerOrphanSweeper}, FR11) and the aged
 * environment cleaner (factory-serve delta, NFR-R2) can find factory objects by
 * label alone — the same "prune by ownership marker" discipline worktree pruning
 * already uses — plus {@link #TASK_LABEL}={@code <environmentKey>} so a single
 * task's three objects can be selected together.
 *
 * <p>Object names are derived deterministically from the sanitized environment
 * key (never a raw task id): given the same key, materialize and dispose compute
 * the identical names, so teardown never depends on state the factory must carry
 * across a crash. The key is already {@code TaskIdSanitizer}-clean, so the
 * derived names satisfy Docker's {@code [a-zA-Z0-9][a-zA-Z0-9_.-]*} grammar.
 *
 * <p>Implements FR3, FR11 of add-sandbox-core.
 */
final class FactoryDockerLabels {

    /** Present on every factory-created Docker object; the sole orphan-sweep filter. */
    static final String FACTORY_LABEL = "com.github.oinsio.gnomish.factory";

    /** Carries the environment key, so one task's objects can be selected together. */
    static final String TASK_LABEL = "com.github.oinsio.gnomish.task";

    private static final String PREFIX = "gnomish";

    private FactoryDockerLabels() {}

    static String networkName(String environmentKey) {
        return PREFIX + "-net-" + environmentKey;
    }

    static String volumeName(String environmentKey) {
        return PREFIX + "-vol-" + environmentKey;
    }

    static String containerName(String environmentKey) {
        return PREFIX + "-box-" + environmentKey;
    }

    /** The egress-guard container's name for this environment (design D4, FR7). */
    static String guardName(String environmentKey) {
        return PREFIX + "-guard-" + environmentKey;
    }

    /** The prefix every factory container name carries; used to recover the key from a listed name. */
    static final String CONTAINER_PREFIX = PREFIX + "-box-";

    /**
     * Recovers the environment key from a factory container name produced by
     * {@link #containerName}, so the aged-environment reaper can match a listed
     * container against the held-key set. Returns empty for a name that is not a
     * factory container name.
     */
    static Optional<String> keyFromContainerName(String name) {
        return name.startsWith(CONTAINER_PREFIX)
                ? Optional.of(name.substring(CONTAINER_PREFIX.length()))
                : Optional.empty();
    }

    /** The {@code key=value} form docker's {@code --label} flag expects. */
    static String factoryLabelAssignment() {
        return FACTORY_LABEL + "=true";
    }

    /** The {@code key=value} form binding {@link #TASK_LABEL} to {@code environmentKey}. */
    static String taskLabelAssignment(String environmentKey) {
        return TASK_LABEL + "=" + environmentKey;
    }

    /** The {@code label=key} form docker's {@code --filter} flag expects to select all factory objects. */
    static String factoryLabelFilter() {
        return "label=" + FACTORY_LABEL;
    }
}
