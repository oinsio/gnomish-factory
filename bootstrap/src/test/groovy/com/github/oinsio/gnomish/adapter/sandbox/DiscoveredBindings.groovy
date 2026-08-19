package com.github.oinsio.gnomish.adapter.sandbox

import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.CapabilityPassport

/**
 * Test access to the package-private {@link SandboxBindingDiscovery}, for the specs outside this
 * package that need the registry the real classpath produces — the composition-root specs that
 * construct {@code ManualRunRunner} directly rather than through the Spring context.
 *
 * Deliberately the real discovery pass rather than a hand-built registry: those specs assert the
 * production dispatch, so the bindings they plan over should be the ones the distribution actually
 * ships (FR3, D6 of open-adapter-binding-registry).
 */
class DiscoveredBindings {

    /** The bindings this build's classpath contributes, ratified by the production trust table. */
    static AdapterBindingRegistry real() {
        SandboxBindingDiscovery.discover()
    }

    /** The class-loader-explicit form, for staging a backend's presence or absence (M3, M4). */
    static AdapterBindingRegistry from(ClassLoader loader, Map<String, CapabilityPassport> trustTable) {
        SandboxBindingDiscovery.discover(loader, trustTable)
    }
}
