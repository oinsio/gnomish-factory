package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.sandbox.BindingNames;
import com.github.oinsio.gnomish.sandbox.CapabilityPassport;
import com.github.oinsio.gnomish.sandbox.SandboxBindingProvider;

/**
 * Contributes the {@code container} binding from this backend module (FR3 of
 * open-adapter-binding-registry): one per-task container that sees only the task
 * working copy, an allowlisted environment, and the guarded network route — the
 * default binding when the operator configures none (D13 of add-sandbox-core).
 *
 * <p>This is what the change buys: the binding and its passport now travel with
 * the module that implements the backend, so removing {@code :sandbox:docker}
 * from a distribution removes the {@code container} binding cleanly instead of
 * leaving a dangling core enum constant (M3). The declaration is still ratified
 * against the core trust table before it is registered — this module proposes a
 * passport, it does not decide one (D2, FR10).
 *
 * <p>Declared without touching the Docker CLI or daemon (FR2), so enumerating the
 * bindings and reconciling stage needs stay daemon-free.
 *
 * <p>Implements FR1, FR2, FR3 of open-adapter-binding-registry; FR3, FR14 of
 * add-sandbox-core.
 */
public final class ContainerBindingProvider implements SandboxBindingProvider {

    /** Public no-arg constructor: the {@code ServiceLoader} contract (D1, D5). */
    public ContainerBindingProvider() {}

    @Override
    public String configName() {
        return BindingNames.CONTAINER;
    }

    @Override
    public CapabilityPassport passport() {
        return CapabilityPassport.container();
    }
}
