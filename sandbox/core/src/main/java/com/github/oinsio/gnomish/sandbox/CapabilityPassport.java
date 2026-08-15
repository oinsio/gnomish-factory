package com.github.oinsio.gnomish.sandbox;

/**
 * An execution-environment adapter's machine-readable capability passport
 * (design D8): the four dimensions the factory reconciles a stage's declared
 * needs against before starting a stage — refusing fail-closed on any mismatch,
 * with repo declarations allowed only to tighten (FR14). The reconciliation
 * engine itself is a later task group; this change fixes the passport shape and
 * the host adapter's honest declaration.
 *
 * <p>Implements FR14 of add-sandbox-core.
 *
 * @param isolation the isolation boundary between a gnome-product process and
 *     the factory host
 * @param egressControlled whether the adapter forces all egress through a
 *     default-deny guard (the host adapter cannot — the network is open)
 * @param taskToTaskBoundary whether one task's environment is isolated from
 *     another's (the host adapter shares the one host)
 * @param dockerInside whether a workload may run Docker inside the environment
 */
public record CapabilityPassport(
        IsolationLevel isolation, boolean egressControlled, boolean taskToTaskBoundary, boolean dockerInside) {

    public CapabilityPassport {
        requireNonNullIsolation(isolation);
    }

    /**
     * The host adapter's honest passport (FR2): no isolation, no egress
     * control, no task-to-task boundary; a workload may use the host's Docker
     * because nothing isolates it from the host in the first place.
     *
     * @return the fixed host passport; never null
     */
    public static CapabilityPassport hostNoIsolation() {
        return new CapabilityPassport(IsolationLevel.NONE, false, false, true);
    }

    /**
     * The container adapter's passport (FR3, design D2): container isolation, all
     * egress forced through the default-deny guard, one task's environment
     * isolated from another's (internal-only network plus a per-task volume) — but
     * <em>no</em> docker-inside support, since this change ships {@code runc} by
     * default and integrates no sysbox/kubedock (NG5). A stage that declares a
     * {@code docker-inside} need therefore fails reconciliation against the
     * default container binding and must be bound elsewhere by the operator.
     *
     * <p>Defined here as the single source of the container capability truth,
     * shared by {@link AdapterBinding#passport()} (planning-time reconciliation)
     * and the container adapter's own {@code passport()} (task group 4).
     *
     * @return the fixed container passport; never null
     */
    public static CapabilityPassport container() {
        return new CapabilityPassport(IsolationLevel.CONTAINER, true, true, false);
    }

    /**
     * Fails fast on a null {@code isolation}: a passport always names a level.
     * Kept as an explicit static method rather than inline in the compact
     * constructor because PIT's record filter suppresses mutations inside a
     * record's canonical constructor, which would exempt this from the mutation
     * gate.
     */
    @SuppressWarnings({"ConstantValue", "ConstantConditions"}) // defensive: guards construction
    // paths NullAway cannot see (e.g. Groovy specs), where a null isolation would otherwise
    // reach here unchecked
    private static void requireNonNullIsolation(IsolationLevel isolation) {
        if (isolation == null) {
            throw new NullPointerException("CapabilityPassport.isolation must not be null");
        }
    }
}
