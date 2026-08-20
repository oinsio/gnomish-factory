package com.github.oinsio.gnomish.adapter.sandbox

import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.IsolationLevel
import com.github.oinsio.gnomish.sandbox.SandboxBindingProvider

/**
 * A stand-in first-party sandbox backend: the binding a future module (colima-vm, gha, cloud) will
 * contribute, staged here through the ordinary SPI so the extension point is proven rather than
 * asserted (M4 of open-adapter-binding-registry).
 *
 * No production source names this class, and the build ships no {@code META-INF/services} entry for
 * it — a spec stages the entry on a loader of its own, exactly as a backend module's jar would
 * carry it.
 */
class StubVmBindingProvider implements SandboxBindingProvider {

    /** The config name an operator would write in {@code factory.bindings.*}. */
    static final String CONFIG_NAME = 'vm'

    /** What this stand-in backend claims: isolated and egress-controlled, with docker inside. */
    static final CapabilityPassport PASSPORT = new CapabilityPassport(IsolationLevel.CONTAINER, true, true, true)

    @Override
    String configName() {
        CONFIG_NAME
    }

    @Override
    CapabilityPassport passport() {
        PASSPORT
    }
}
