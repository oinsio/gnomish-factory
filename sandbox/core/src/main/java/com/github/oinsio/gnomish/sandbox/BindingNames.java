package com.github.oinsio.gnomish.sandbox;

/**
 * The two binding config names the core privileges (design D3, D13): {@code
 * container} is the default binding when the operator configures none, and
 * {@code host} is the explicit unsandboxed opt-in. Every other binding name is
 * data discovered from the classpath — these two are named in core because the
 * container-by-default rule and the host opt-in are core policy, not a backend's.
 *
 * <p>The coupling is pre-existing, not introduced by the registry: D13 already
 * made these two names privileged. A future backend adds no constant here.
 *
 * <p>Implements FR3, FR4 of open-adapter-binding-registry.
 */
public final class BindingNames {

    /** The unsandboxed host binding, contributed from {@code :sandbox:core} (FR3). */
    public static final String HOST = "host";

    /** The container binding, contributed from {@code :sandbox:docker}; the default (D13, FR4). */
    public static final String CONTAINER = "container";

    private BindingNames() {}
}
