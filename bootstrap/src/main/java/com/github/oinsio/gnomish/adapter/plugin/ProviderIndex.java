package com.github.oinsio.gnomish.adapter.plugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Indexes one {@code ServiceLoader} pass into a discriminator-keyed registry, shared by every port's
 * discovery pass ({@code TrackerAdapterDiscovery}, {@code CheckClientDiscovery}) so the fail-fast
 * rule — a blank discriminator, or two providers claiming the same one, aborts registry build rather
 * than picking silently — is written once (NFR-R1 of add-plugin-architecture).
 *
 * <p>Lives beside {@link ProviderDiscoveryReport} as the same kind of thing: port-agnostic discovery
 * infrastructure, taking the SPI type as a generic parameter so it depends on neither {@code
 * adapter.tracker} nor {@code adapter.check}.
 */
public final class ProviderIndex {

    private ProviderIndex() {}

    /**
     * Indexes one discovery pass, keyed by each entry's own discriminator.
     *
     * @param port the port name used in fail-fast messages, so an operator sees which registry
     *     broke; never null
     * @param discriminatorName the discriminator accessor's name, as it reads in an error message
     *     (e.g. {@code "type"} for a {@code type()} accessor); never null
     * @param discovered the factories a discovery pass produced, in encounter order; never null
     * @param discriminator reads one factory's discriminator; never null
     * @param <T> the port's SPI factory type
     * @return the providers keyed by discriminator; never null
     * @throws IllegalStateException if a factory declares a blank discriminator, or two factories
     *     claim the same one
     */
    public static <T> Map<String, T> index(
            String port, String discriminatorName, Iterable<T> discovered, Function<? super T, String> discriminator) {
        Map<String, T> registry = new LinkedHashMap<>();
        for (T factory : discovered) {
            String key = discriminator.apply(factory);
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("discovered " + port + " provider "
                        + factory.getClass().getName() + " declares no " + discriminatorName
                        + "() discriminator");
            }
            T previous = registry.put(key, factory);
            if (previous != null) {
                throw new IllegalStateException("duplicate " + port + " provider '" + key + "' declared by "
                        + previous.getClass().getName() + " and "
                        + factory.getClass().getName());
            }
        }
        return Map.copyOf(registry);
    }
}
