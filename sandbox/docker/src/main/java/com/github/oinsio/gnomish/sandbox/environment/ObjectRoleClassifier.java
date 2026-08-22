package com.github.oinsio.gnomish.sandbox.environment;

/**
 * Derives a listed object's {@link ObjectRole} and base task key from its own name and the
 * environment key its {@code TASK_LABEL} carries — never by recomputing expected names from a
 * live-task snapshot (`execution-environment` delta, "Role recovered from the object itself").
 * The environment key is the label value verbatim (which for a fresh judge/verification
 * environment already carries the {@code -j}/{@code -v} suffix, `sandbox-lifecycle` design D1);
 * {@link #baseTaskKey} strips it so the caller can check liveness against the task that owns it.
 */
final class ObjectRoleClassifier {

    private static final String JUDGE_SUFFIX = "-j";
    private static final String VERIFICATION_SUFFIX = "-v";

    private ObjectRoleClassifier() {}

    /**
     * Classifies the object's lifecycle role.
     *
     * @param kind the listed object's Docker type; never null
     * @param name the object's own name; never null
     * @param environmentKey this object's {@code TASK_LABEL} value (may itself be {@code
     *     <base>-j}/{@code <base>-v}); never null
     * @return the classified role; never null
     */
    static ObjectRole classify(ObjectKind kind, String name, String environmentKey) {
        return switch (kind) {
            case CONTAINER -> classifyContainer(name, environmentKey);
            case VOLUME ->
                name.startsWith(FactoryDockerLabels.VOLUME_PREFIX) ? boxRole(environmentKey) : ObjectRole.UNRECOGNIZED;
            case NETWORK ->
                name.startsWith(FactoryDockerLabels.NETWORK_PREFIX) ? boxRole(environmentKey) : ObjectRole.UNRECOGNIZED;
        };
    }

    private static ObjectRole classifyContainer(String name, String environmentKey) {
        if (name.startsWith(FactoryDockerLabels.CONTAINER_PREFIX)) {
            return boxRole(environmentKey);
        }
        if (name.startsWith(FactoryDockerLabels.GUARD_PREFIX)) {
            return ObjectRole.GUARD;
        }
        // A factory-labelled container matching no factory name pattern is the anonymous
        // seed-clone helper (`execution-environment` delta) — the only container type Docker
        // names for the factory (Docker's own auto-generated name, never `gnomish-*`).
        return ObjectRole.SEED_HELPER;
    }

    private static ObjectRole boxRole(String environmentKey) {
        if (environmentKey.endsWith(JUDGE_SUFFIX)) {
            return ObjectRole.JUDGE;
        }
        if (environmentKey.endsWith(VERIFICATION_SUFFIX)) {
            return ObjectRole.VERIFICATION;
        }
        return ObjectRole.MAIN_BOX;
    }

    /**
     * Strips a {@code -j}/{@code -v} suffix so a judge/verification environment's key resolves
     * to the base task the liveness oracle's live-key set is keyed by.
     *
     * @param environmentKey the object's own environment key (label value); never null
     * @return the base task key; never null
     */
    static String baseTaskKey(String environmentKey) {
        if (environmentKey.endsWith(JUDGE_SUFFIX)) {
            return environmentKey.substring(0, environmentKey.length() - JUDGE_SUFFIX.length());
        }
        if (environmentKey.endsWith(VERIFICATION_SUFFIX)) {
            return environmentKey.substring(0, environmentKey.length() - VERIFICATION_SUFFIX.length());
        }
        return environmentKey;
    }
}
