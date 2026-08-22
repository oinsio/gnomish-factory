package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry;
import com.github.oinsio.gnomish.sandbox.BindingNames;
import com.github.oinsio.gnomish.sandbox.BindingProperties;
import com.github.oinsio.gnomish.sandbox.BindingTrustTable;
import com.github.oinsio.gnomish.sandbox.HostBindingProvider;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The container-dispatch collaborators every {@code take} entry point needs to route a fresh
 * claim through the container assembly instead of the host one (FR1 of
 * add-serve-sandbox-lifecycle): the operator's per-stage bindings, the sandbox config, the
 * classpath-discovered adapter registry, the Docker-availability probe {@link
 * SandboxModeSelector#plan} consults, and the {@code tracked}-labelling container support
 * factory itself (as opposed to {@code run}'s {@code manual}-labelling one — the two lambdas
 * differ only in the {@code OwnershipMode} they close over). Bundled as one object so the
 * plumbing from {@code ManualRunRunner} down through {@code take}/{@code serve}'s dispatch chain
 * carries one parameter instead of six.
 *
 * <p>Public so {@code app.serve.TakeSlotRunner} — a different package — can forward one opaquely
 * from {@code serve}'s own wiring down into {@link TakeClaimAndWorkFactory#forSlot}, mirroring
 * why {@link TakeClaimAndWork} itself is public (see its class javadoc).
 *
 * <p>Implements FR1, FR2, FR8 of add-serve-sandbox-lifecycle.
 */
public record ContainerTakeSupport(
        FactoryProperties factoryProperties,
        BindingProperties bindingProperties,
        SandboxProperties sandboxProperties,
        AdapterBindingRegistry bindingRegistry,
        BooleanSupplier dockerProbe,
        ContainerSupportFactory containerSupportFactory) {

    /**
     * A host-only bundle for the take entry points' collaborator-light test constructions (mirroring
     * why {@code TakeDisposition}'s own heartbeat-free constructor exists): the default binding is
     * the explicit {@code host} name, so {@link SandboxModeSelector#plan} always resolves {@code
     * HOST} regardless of the pipeline under test, and its container support factory is never
     * actually invoked.
     */
    static ContainerTakeSupport hostOnly() {
        return hostOnly(new FactoryProperties(null, null, null, null, null, null));
    }

    /** As {@link #hostOnly()}, over a caller-supplied {@code factoryProperties}. */
    static ContainerTakeSupport hostOnly(FactoryProperties factoryProperties) {
        var registry =
                AdapterBindingRegistry.ratified(List.of(new HostBindingProvider()), BindingTrustTable.firstParty());
        var bindings = new BindingProperties(BindingNames.HOST, Map.of());
        var sandboxProperties =
                new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null);
        return new ContainerTakeSupport(
                factoryProperties, bindings, sandboxProperties, registry, () -> false, (_, _, _, _, _, _, _) -> {
                    throw new IllegalStateException("host-only ContainerTakeSupport never builds container support");
                });
    }
}
