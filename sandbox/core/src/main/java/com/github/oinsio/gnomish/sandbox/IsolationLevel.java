package com.github.oinsio.gnomish.sandbox;

/**
 * The isolation boundary an execution-environment adapter places between a
 * gnome-product process and the factory host (design D8, part of the adapter
 * {@link CapabilityPassport}). The factory reconciles a stage's declared needs
 * against the bound adapter's declared level and refuses fail-closed on a
 * mismatch (FR14; reconciliation itself lands in a later task group).
 *
 * <p>The set is deliberately open to extension: Colima-VM, k8s, and microVM
 * adapters (later changes) add their own levels without changing the port
 * contract. This change ships {@link #NONE} (host adapter) and {@link
 * #CONTAINER} (container adapter, later task group).
 *
 * <p>Implements FR14 of add-sandbox-core.
 */
public enum IsolationLevel {

    /**
     * No isolation: the process runs directly on the factory host, seeing its
     * filesystem and network. The host adapter declares this honestly — the env
     * allowlist bounds environment variables only, never filesystem access
     * (FR2).
     */
    NONE,

    /**
     * Container isolation: the process runs inside a per-task container that
     * sees only the task working copy, an allowlisted environment, and the
     * single guarded network route (FR3).
     */
    CONTAINER
}
