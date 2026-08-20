package com.github.oinsio.gnomish.adapter.sandbox;

import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry;
import com.github.oinsio.gnomish.sandbox.BindingTrustTable;
import com.github.oinsio.gnomish.sandbox.CapabilityPassport;
import com.github.oinsio.gnomish.sandbox.SandboxBindingProvider;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Builds the adapter-binding registry from one {@link ServiceLoader} pass over the first-party
 * {@link SandboxBindingProvider}s the classpath contributes (design D1 of
 * open-adapter-binding-registry). A sandbox backend module becomes selectable by shipping a {@code
 * META-INF/services/com.github.oinsio.gnomish.sandbox.SandboxBindingProvider} entry, with no edit
 * to any core source file beyond its one-line trust-table registration (FR1, G1, M1).
 *
 * <p>Deliberately shaped like {@code TrackerAdapterDiscovery} — {@code discover()} /
 * {@code discover(ClassLoader)} over a pure indexing step — and deliberately <em>not</em> the same
 * trust model. {@code ServiceLoader} is the enumerator only: every decision (duplicate names,
 * ratification against the core trust table, the default) is {@link AdapterBindingRegistry}'s own
 * code, and a discovered provider whose id or passport the core does not vouch for is refused
 * rather than registered (D2, FR7, FR10, NFR-S1). The sandbox is a trust boundary, so this
 * mechanism is not wired to the third-party plugin discovery of add-plugin-architecture.
 *
 * <p>Lives in {@code :bootstrap} because building a port's registry is composition; the SPI type,
 * the registry and its ratification rules live in {@code :sandbox:core}.
 *
 * <p>Implements FR1, FR7, FR10, NFR-R1, NFR-S1 of open-adapter-binding-registry.
 */
final class SandboxBindingDiscovery {

    private SandboxBindingDiscovery() {}

    /**
     * Discovers every {@link SandboxBindingProvider} visible to the SPI's own class loader — the
     * loader that also carries the bundled backend modules — and ratifies it against the production
     * trust table.
     *
     * @return the frozen registry; never null
     */
    static AdapterBindingRegistry discover() {
        return discover(SandboxBindingProvider.class.getClassLoader(), BindingTrustTable.firstParty());
    }

    /**
     * The class-loader-explicit form, so a spec can stage a first-party backend's {@code
     * META-INF/services} entry on a loader of its own — or stage the <em>absence</em> of one — and
     * prove that the mechanism needs no edit either way (M3, M4).
     *
     * @param loader the loader whose service entries are scanned; never null
     * @param trustTable trusted binding id → expected passport; never null
     * @return the frozen registry; never null
     */
    static AdapterBindingRegistry discover(ClassLoader loader, Map<String, CapabilityPassport> trustTable) {
        return AdapterBindingRegistry.ratified(ServiceLoader.load(SandboxBindingProvider.class, loader), trustTable);
    }
}
