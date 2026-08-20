package com.github.oinsio.gnomish.sandbox;

/**
 * Contributes the {@code host} binding (FR3 of open-adapter-binding-registry):
 * the unsandboxed adapter — worktree working copy, local subprocesses, no
 * isolation — reachable only as an explicit operator opt-in, never as a silent
 * fallback (D13 of add-sandbox-core).
 *
 * <p>Lives in {@code :sandbox:core} rather than a backend module because the host
 * binding is the core's own opt-out of isolation: the name is privileged by D13
 * ({@link BindingNames#HOST}), and its passport — "nothing is isolated" — is the
 * one declaration no backend needs to be present to make honestly. It contributes
 * through the same {@link SandboxBindingProvider} SPI and the same {@code
 * META-INF/services} entry as any other backend, and is ratified against the same
 * trust table, so it holds no privileged construction path.
 *
 * <p>Implements FR1, FR3 of open-adapter-binding-registry; FR2, FR14 of
 * add-sandbox-core.
 */
public final class HostBindingProvider implements SandboxBindingProvider {

    /** Public no-arg constructor: the {@code ServiceLoader} contract (D1, D5). */
    public HostBindingProvider() {}

    @Override
    public String configName() {
        return BindingNames.HOST;
    }

    @Override
    public CapabilityPassport passport() {
        return CapabilityPassport.hostNoIsolation();
    }
}
