package com.github.oinsio.gnomish.sandbox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The discovered set of adapter bindings (design D1, D2, D6 of
 * open-adapter-binding-registry): built once at startup from the {@link
 * SandboxBindingProvider}s the classpath contributes, ratified against the
 * core-owned trust table, and then frozen. It replaces the sealed {@code
 * AdapterBinding} enum core used to enumerate (FR1).
 *
 * <p>Both halves of the build are refusals, never silent picks (NFR-R1): two
 * providers claiming one config name abort with the conflict named (FR8), and a
 * provider whose id is absent from the trust table, or whose declared passport
 * differs from the trusted one, aborts with the mismatch and the fix named (FR7,
 * FR10). A registered binding always carries the <em>table's</em> passport, so
 * the provider's declaration is a cross-check and never the authority.
 *
 * <p>Both the provider list and the trust table are parameters rather than
 * ambient state: the composition root passes a {@code ServiceLoader} pass and
 * {@link BindingTrustTable#firstParty()}, specs pass their own — which is what
 * lets a stub first-party backend be staged end-to-end without editing the
 * mechanism (M4).
 *
 * <p>Implements FR1, FR5, FR7, FR8, FR10, NFR-R1, NFR-S1 of
 * open-adapter-binding-registry.
 */
public final class AdapterBindingRegistry {

    private final Map<String, AdapterBinding> bindings;

    private AdapterBindingRegistry(Map<String, AdapterBinding> bindings) {
        this.bindings = bindings;
    }

    /**
     * Indexes {@code providers} by config name and ratifies each against {@code
     * trustTable}, in encounter order. Pure over its two arguments — it loads no
     * class, touches no classpath and instantiates no backend adapter (FR2).
     *
     * @param providers the providers a discovery pass produced, in encounter order;
     *     never null
     * @param trustTable trusted binding id → expected passport; never null
     * @return the frozen registry; never null
     * @throws IllegalStateException if a provider declares a blank config name, two
     *     providers claim one config name, a config name is absent from the trust
     *     table, or a declared passport differs from the trusted one
     */
    public static AdapterBindingRegistry ratified(
            Iterable<SandboxBindingProvider> providers, Map<String, CapabilityPassport> trustTable) {
        Map<String, AdapterBinding> indexed = new LinkedHashMap<>();
        Map<String, String> declaredBy = new LinkedHashMap<>();
        for (SandboxBindingProvider provider : providers) {
            String name = requireConfigName(provider);
            String previous = declaredBy.put(name, provider.getClass().getName());
            if (previous != null) {
                throw new IllegalStateException("duplicate sandbox binding '" + name + "' declared by " + previous
                        + " and " + provider.getClass().getName()
                        + " — remove one of the two modules from the classpath");
            }
            indexed.put(name, new AdapterBinding(name, ratify(name, provider, trustTable)));
        }
        return new AdapterBindingRegistry(Map.copyOf(indexed));
    }

    /**
     * The binding named {@code configName}, failing fast with the discovered options
     * and the fix named when no provider contributes it (FR5, UX1).
     *
     * @param configName the configured binding name; never null
     * @return the discovered binding; never null
     * @throws IllegalArgumentException if no discovered binding carries that name
     */
    public AdapterBinding require(String configName) {
        AdapterBinding binding = find(configName);
        if (binding == null) {
            throw new IllegalArgumentException("unknown adapter binding '" + configName + "'; discovered bindings are "
                    + names() + " — name one of them in factory.bindings.*, or add the module contributing '"
                    + configName + "' to the classpath");
        }
        return binding;
    }

    /**
     * The binding named {@code configName}, or null when none is discovered — the
     * lookup for callers that phrase their own refusal, such as the absent-container
     * default (D4).
     *
     * @param configName the binding name to look up; never null
     * @return the discovered binding, or null
     */
    public @Nullable AdapterBinding find(String configName) {
        return bindings.get(configName);
    }

    /**
     * The discovered binding names in discovery order, as an operator sees them in
     * every refusal message.
     *
     * @return the config names; never null, immutable
     */
    public Set<String> names() {
        return bindings.keySet();
    }

    /**
     * The discovered bindings keyed by config name, for the startup discovery report
     * (NFR-O1).
     *
     * @return the bindings; never null, immutable
     */
    public Map<String, AdapterBinding> bindings() {
        return bindings;
    }

    private static String requireConfigName(SandboxBindingProvider provider) {
        String name = provider.configName();
        if (name == null || name.isBlank()) {
            throw new IllegalStateException(
                    "discovered sandbox binding provider " + provider.getClass().getName()
                            + " declares no configName() — a binding must name itself to be configurable");
        }
        return name;
    }

    /**
     * The trust gate (D2): the id must be registered and the declared passport must
     * equal the registered one. Returns the <em>table's</em> passport, so what the
     * registry stores is the ratified value rather than the provider's copy of it.
     */
    private static CapabilityPassport ratify(
            String name, SandboxBindingProvider provider, Map<String, CapabilityPassport> trustTable) {
        CapabilityPassport expected = trustTable.get(name);
        if (expected == null) {
            throw new IllegalStateException("untrusted sandbox binding '" + name + "' declared by "
                    + provider.getClass().getName() + "; trusted bindings are " + trustTable.keySet()
                    + " — remove the module from the classpath, or register the binding and its expected"
                    + " passport in the core trust table");
        }
        CapabilityPassport declared = provider.passport();
        if (!expected.equals(declared)) {
            throw new IllegalStateException("sandbox binding '" + name + "' declared by "
                    + provider.getClass().getName() + " claims passport " + declared + " but the core trust table"
                    + " expects " + expected
                    + " — the classpath carries an unexpected build of this backend; restore the trusted module,"
                    + " or update the core trust table if the change is intended");
        }
        return expected;
    }
}
