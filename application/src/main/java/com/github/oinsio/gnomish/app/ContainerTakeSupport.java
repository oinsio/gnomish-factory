package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry;
import com.github.oinsio.gnomish.sandbox.BindingNames;
import com.github.oinsio.gnomish.sandbox.BindingProperties;
import com.github.oinsio.gnomish.sandbox.BindingTrustTable;
import com.github.oinsio.gnomish.sandbox.HostBindingProvider;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import java.util.List;
import java.util.Map;

/**
 * The container-dispatch collaborators every {@code take} entry point needs to route a fresh
 * claim through the container assembly instead of the host one (FR1 of
 * add-serve-sandbox-lifecycle): the execution-mode selector {@code TakeWorkRouter} asks for the
 * run's plan, and the {@code tracked}-labelling container support factory itself (as opposed to
 * {@code run}'s {@code manual}-labelling one — the two lambdas differ only in the {@code
 * OwnershipMode} they close over). Bundled as one object so the plumbing from {@code
 * ManualRunRunner} down through {@code take}/{@code serve}'s dispatch chain carries one parameter
 * instead of two.
 *
 * <p>The selector is the root's one instance (design D22 of
 * supervise-daemon-loops-and-embed-dashboard): this bundle declares no accessor for the bindings,
 * the sandbox config, the binding registry or the runtime probe, so no consumer can read them back
 * out and decide the execution mode by a second route. The support factory already holds the
 * installation's settings, so no property set travels to its {@code create} (FR20 of
 * make-checkpoint-gate-durable).
 *
 * <p>Public so {@code app.serve.TakeSlotRunner} — a different package — can forward one opaquely
 * from {@code serve}'s own wiring down into {@link TakeClaimAndWorkFactory#forSlot}, mirroring
 * why {@link TakeClaimAndWork} itself is public (see its class javadoc).
 *
 * <p>Implements FR1, FR2, FR8 of add-serve-sandbox-lifecycle; FR18 of
 * supervise-daemon-loops-and-embed-dashboard.
 *
 * @param modeSelector the root's execution-mode selector; never null
 * @param containerSupportFactory builds a run's container support, stamping {@code tracked};
 *     never null
 */
public record ContainerTakeSupport(SandboxModeSelector modeSelector, ContainerSupportFactory containerSupportFactory) {

    /**
     * A host-only bundle for the take entry points' collaborator-light test constructions (mirroring
     * why {@code TakeDisposition}'s own heartbeat-free constructor exists): the only discovered
     * binding and the default are the explicit {@code host} name, so the selector always resolves
     * {@code HOST} regardless of the pipeline under test and never reaches the container-runtime
     * probe — which therefore refuses to be asked rather than answering (an answer no plan can read
     * would be a claim nothing checks) — and its container support factory is never invoked.
     */
    static ContainerTakeSupport hostOnly() {
        var registry =
                AdapterBindingRegistry.ratified(List.of(new HostBindingProvider()), BindingTrustTable.firstParty());
        var bindings = new BindingProperties(BindingNames.HOST, Map.of());
        var sandboxProperties =
                new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null);
        var selector = new SandboxModeSelector(bindings, sandboxProperties, registry, () -> {
            throw new IllegalStateException("a host-only selector never probes the container runtime");
        });
        return new ContainerTakeSupport(selector, (_, _, _, _, _) -> {
            throw new IllegalStateException("host-only ContainerTakeSupport never builds container support");
        });
    }
}
