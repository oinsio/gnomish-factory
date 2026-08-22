package com.github.oinsio.gnomish.sandbox.environment;

/**
 * The ownership mode stamped on every factory-created Docker object (design D5, FR2 of
 * add-serve-sandbox-lifecycle): {@link #TRACKED} for claim-backed tasks (governed by the
 * claim-heartbeat liveness oracle), {@link #MANUAL} for {@code gnomish run} sessions (governed by
 * age alone — {@code run} has no tracker claim to check). Public so the app-layer assemblies that
 * construct {@link ContainerEnvironments} across entry points (run, take, serve) can name it; the
 * label plumbing it feeds ({@link ObjectOwnership}, {@link FactoryDockerLabels}) stays internal.
 */
public enum OwnershipMode {
    TRACKED("tracked"),
    MANUAL("manual");

    private final String label;

    OwnershipMode(String label) {
        this.label = label;
    }

    /** The value stamped into {@link FactoryDockerLabels#MODE_LABEL}. */
    String label() {
        return label;
    }
}
