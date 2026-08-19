package com.github.oinsio.gnomish.sandbox;

import java.util.Map;

/**
 * The core-owned trust table that gates binding discovery (design D2 of
 * open-adapter-binding-registry): trusted first-party binding id → the {@link
 * CapabilityPassport} that id is expected to carry. A binding's id <em>is</em> its
 * {@code configName()} — one identifier, no separate id space.
 *
 * <p>This is what makes discovery first-party only on a flat classpath (NFR-S1).
 * {@code ServiceLoader} would otherwise load any jar's provider and let it assert
 * a lying passport, and the passport is precisely what reconciliation trusts.
 * {@link AdapterBindingRegistry} therefore ratifies every discovered provider
 * against this table: an id absent from it is rejected, and a declared passport
 * differing from the expected one is rejected — both fail-fast, before any stage
 * runs (FR7, FR10). The provider's declaration is a cross-check; this table is
 * the authority.
 *
 * <p>Registering a new first-party backend is therefore a one-line, reviewed
 * trust registration here — data, not behavior; the binding's definition still
 * lives wholly in the backend's own module (G1, M1). The residual flat-classpath
 * risk — a malicious jar shipping a provider under a trusted id <em>with</em> the
 * expected passport — has no runtime defense post-SecurityManager and is closed
 * at build time by classpath pinning (the dependency-verification change).
 *
 * <p>Implements FR7, FR10, NFR-S1 of open-adapter-binding-registry.
 */
public final class BindingTrustTable {

    private BindingTrustTable() {}

    /**
     * The production trust table: the two bindings this distribution ships, each
     * with the passport it is expected to declare. Sourced from the same {@link
     * CapabilityPassport} factory methods the providers themselves use, so the
     * ratification compares two independently-read copies of one truth rather than
     * a copy against itself.
     *
     * @return trusted binding id → expected passport; never null, immutable
     */
    public static Map<String, CapabilityPassport> firstParty() {
        return Map.of(
                BindingNames.HOST, CapabilityPassport.hostNoIsolation(),
                BindingNames.CONTAINER, CapabilityPassport.container());
    }
}
