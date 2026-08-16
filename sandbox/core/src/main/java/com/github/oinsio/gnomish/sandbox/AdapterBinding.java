package com.github.oinsio.gnomish.sandbox;

/**
 * Which execution-environment adapter the operator binds a stage to (design D8,
 * D13): the two adapters this change ships. A binding is an operator-only choice
 * — the repo may only tighten needs, never name a binding (FR14) — configured by
 * name in {@code factory.bindings.*} and resolved to this enum by {@code
 * BindingResolver}. Each binding carries the fixed {@link CapabilityPassport} the
 * factory reconciles a stage's declared needs against before the stage runs
 * (task 3.2), so reconciliation and segment planning need no live adapter
 * instance.
 *
 * <p>The set grows without a contract change: Colima-VM, k8s, and microVM
 * adapters (later changes) add their own constants and passports behind the same
 * port. This change ships {@link #HOST} and {@link #CONTAINER}.
 *
 * <p>Implements FR14 of add-sandbox-core.
 */
public enum AdapterBinding {

    /**
     * The host adapter (FR2): worktree working copy, local subprocesses, no
     * isolation. Available only as an explicit operator opt-in — never a silent
     * fallback (D13).
     */
    HOST("host") {
        @Override
        public CapabilityPassport passport() {
            return CapabilityPassport.hostNoIsolation();
        }
    },

    /**
     * The container adapter (FR3): one per-task container that sees only the task
     * working copy, an allowlisted environment, and the guarded network route.
     * The default binding when the operator configures none (D13).
     */
    CONTAINER("container") {
        @Override
        public CapabilityPassport passport() {
            return CapabilityPassport.container();
        }
    };

    private final String configName;

    AdapterBinding(String configName) {
        this.configName = configName;
    }

    /**
     * The lower-case name this binding is spelled with in {@code
     * factory.bindings.*} configuration.
     *
     * @return the config spelling; never null
     */
    public String configName() {
        return configName;
    }

    /**
     * This binding's fixed capability passport, reconciled fail-closed against a
     * stage's declared needs before the stage runs (FR14). Returned by value from
     * the single {@link CapabilityPassport} factory method for this binding, so
     * the container adapter's own {@code passport()} and this method never drift
     * (and the enum holds no mutable capability state).
     *
     * @return the passport; never null
     */
    public abstract CapabilityPassport passport();

    /**
     * Resolves a {@code factory.bindings.*} binding name to its enum constant,
     * failing fast with the valid options named when the name is unknown — an
     * operator configuration mistake, surfaced at startup rather than mid-task
     * (UX2). The match is exact and case-sensitive: the config grammar is fixed
     * lower-case, so a near-miss like {@code Host} is a real typo worth
     * reporting, not silently coerced.
     *
     * @param name the configured binding name; never null
     * @return the matching binding; never null
     * @throws IllegalArgumentException if no binding has that config name
     */
    public static AdapterBinding parse(String name) {
        for (AdapterBinding binding : values()) {
            if (binding.configName.equals(name)) {
                return binding;
            }
        }
        throw new IllegalArgumentException("unknown adapter binding '" + name + "'; valid bindings are "
                + HOST.configName + ", " + CONTAINER.configName);
    }
}
