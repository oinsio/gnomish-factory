package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeProbe
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.BindingTrustTable
import com.github.oinsio.gnomish.sandbox.HostBindingProvider
import com.github.oinsio.gnomish.sandbox.SandboxProperties

/**
 * The host-only {@link ContainerTakeSupport} the take entry points' collaborator-light specs pass
 * where the root hands its container dispatch. Lives in the test fixtures, not beside the record:
 * production never builds a host-only bundle, and a factory only specs call is the "test
 * constructor" shape `process-invariants.md` removes from production code.
 */
final class ContainerTakeSupportFixture {

    private ContainerTakeSupportFixture() {}

    /**
     * The only discovered binding and the default are the explicit {@code host} name, so the
     * selector always resolves {@code HOST} regardless of the pipeline under test and never reaches
     * the container-runtime probe — which therefore refuses to be asked rather than answering (an
     * answer no plan can read would be a claim nothing checks) — and its container support factory
     * is never invoked.
     */
    static ContainerTakeSupport hostOnly() {
        def registry = AdapterBindingRegistry.ratified([new HostBindingProvider()], BindingTrustTable.firstParty())
        def bindings = new BindingProperties(BindingNames.HOST, [:])
        def sandboxProperties = new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null)
        def probe = {
            throw new IllegalStateException('a host-only selector never probes the container runtime')
        } as ContainerRuntimeProbe
        def selector = new SandboxModeSelector(bindings, sandboxProperties, registry, probe)
        new ContainerTakeSupport(selector, { a, b, c, d, e ->
            throw new IllegalStateException('host-only ContainerTakeSupport never builds container support')
        } as ContainerSupportFactory)
    }
}
