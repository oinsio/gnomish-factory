package com.github.oinsio.gnomish.sandbox.environment;

import org.jspecify.annotations.Nullable;

/**
 * One listed object's classification, derived from its own labels and name (`
 * execution-environment` delta, "Role recovered from the object itself") — never from a live-task
 * snapshot: its environment key (the {@code TASK_LABEL} value verbatim, which for a fresh
 * judge/verification environment already carries the {@code -j}/{@code -v} suffix), the base
 * task key that suffix resolves to, its {@link ObjectRole}, and its {@link OwnershipMode}.
 *
 * @param environmentKey this object's own environment key; never blank
 * @param baseTaskKey the base task key the liveness oracle's live-key set is keyed by; never blank
 * @param role the classified lifecycle role; never null
 * @param mode the classified ownership mode; never null
 */
record SandboxLifecycleClassification(String environmentKey, String baseTaskKey, ObjectRole role, OwnershipMode mode) {

    /**
     * Classifies a listed object, or returns null when it cannot be.
     *
     * @param object the listed object; never null
     * @return the classification, or null when the object carries no {@code TASK_LABEL} — a
     *     degenerate object this evaluator cannot meaningfully classify, silently skipped
     */
    static @Nullable SandboxLifecycleClassification of(ListedDockerObject object) {
        String environmentKey = object.labels().get(FactoryDockerLabels.TASK_LABEL);
        if (environmentKey == null || environmentKey.isBlank()) {
            return null;
        }
        ObjectRole role = ObjectRoleClassifier.classify(object.kind(), object.name(), environmentKey);
        String baseTaskKey = ObjectRoleClassifier.baseTaskKey(environmentKey);
        OwnershipMode mode = ObjectOwnershipClassifier.classify(object.labels());
        return new SandboxLifecycleClassification(environmentKey, baseTaskKey, role, mode);
    }
}
