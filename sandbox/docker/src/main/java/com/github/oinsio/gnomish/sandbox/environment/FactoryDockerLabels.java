package com.github.oinsio.gnomish.sandbox.environment;

import java.util.List;

/**
 * The factory-owned labels and object names stamped on every Docker object the
 * container adapter creates (design D2, FR3, FR11): a network, a volume, and a
 * container per task. Every object carries {@link #FACTORY_LABEL}={@code true} so
 * the sweep-lifecycle policy ({@code SandboxLifecycleSweep}, FR4 of
 * add-serve-sandbox-lifecycle) can find factory objects by
 * label alone — the same "prune by ownership marker" discipline worktree pruning
 * already uses — plus {@link #TASK_LABEL}={@code <environmentKey>} so a single
 * task's three objects can be selected together. {@link #MODE_LABEL} and {@link
 * #PROJECT_LABEL} (design D5, FR2 of add-serve-sandbox-lifecycle) carry the
 * ownership mode and project identity the sweep-lifecycle policy classifies
 * objects by.
 *
 * <p>Object names are derived deterministically from the sanitized environment
 * key (never a raw task id): given the same key, materialize and dispose compute
 * the identical names, so teardown never depends on state the factory must carry
 * across a crash. The key is already {@code TaskIdSanitizer}-clean, so the
 * derived names satisfy Docker's {@code [a-zA-Z0-9][a-zA-Z0-9_.-]*} grammar.
 *
 * <p>Implements FR3, FR11 of add-sandbox-core; FR2 of add-serve-sandbox-lifecycle.
 */
final class FactoryDockerLabels {

    /** Present on every factory-created Docker object; the sole orphan-sweep filter. */
    static final String FACTORY_LABEL = "com.github.oinsio.gnomish.factory";

    /** Carries the environment key, so one task's objects can be selected together. */
    static final String TASK_LABEL = "com.github.oinsio.gnomish.task";

    /** Carries the ownership mode ({@code tracked} | {@code manual}, FR2 of add-serve-sandbox-lifecycle). */
    static final String MODE_LABEL = "com.github.oinsio.gnomish.mode";

    /** Carries the project identity the sweep scopes its listings to (FR8 of add-serve-sandbox-lifecycle). */
    static final String PROJECT_LABEL = "com.github.oinsio.gnomish.project";

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

    /** The prefix every factory guard container name carries (role classification, task 3.1). */
    static final String GUARD_PREFIX = PREFIX + "-guard-";

    /** The prefix every factory volume name carries (role classification, task 3.1). */
    static final String VOLUME_PREFIX = PREFIX + "-vol-";

    /** The prefix every factory network name carries (role classification, task 3.1). */
    static final String NETWORK_PREFIX = PREFIX + "-net-";

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

    /**
     * The {@code label=key=value} filter scoping a listing to one project (FR8 of
     * add-serve-sandbox-lifecycle): combined with {@link #factoryLabelFilter()}, docker ANDs
     * multiple {@code --filter label=} flags, so the pair selects only this project's factory
     * objects — another project's objects are excluded at listing, never merely skipped later.
     */
    static String projectLabelFilter(String projectId) {
        return "label=" + PROJECT_LABEL + "=" + projectId;
    }

    /** The {@code key=value} form binding {@link #MODE_LABEL} to {@code mode}. */
    static String modeLabelAssignment(OwnershipMode mode) {
        return MODE_LABEL + "=" + mode.label();
    }

    /** The {@code key=value} form binding {@link #PROJECT_LABEL} to {@code projectId}. */
    static String projectLabelAssignment(String projectId) {
        return PROJECT_LABEL + "=" + projectId;
    }

    /**
     * The four {@code --label key=value} argv pairs — factory, task, mode, project — every
     * creation command splices in verbatim (FR2 of add-serve-sandbox-lifecycle): the single seam
     * that makes a partially-labelled factory object impossible by construction, since no create
     * command builds its own label flags any other way.
     */
    static List<String> ownershipLabelArgs(String environmentKey, ObjectOwnership ownership) {
        return List.of(
                "--label",
                factoryLabelAssignment(),
                "--label",
                taskLabelAssignment(environmentKey),
                "--label",
                modeLabelAssignment(ownership.mode()),
                "--label",
                projectLabelAssignment(ownership.projectId()));
    }
}
