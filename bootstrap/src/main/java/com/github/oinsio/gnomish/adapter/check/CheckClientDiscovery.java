package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.app.CheckClientFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Builds the check port's provider registry from one {@link ServiceLoader} pass, keyed by each
 * discovered factory's {@link CheckClientFactory#provider()} — the exact mirror of {@code
 * TrackerAdapterDiscovery}, which is the point: the two ports now discover providers the same way
 * (FR5, design D1/D3 of add-plugin-architecture). A jar carrying a {@code
 * META-INF/services/com.github.oinsio.gnomish.app.CheckClientFactory} entry becomes selectable with
 * no edit to any core source file, and the bundled github provider travels that same path — there is
 * no built-in shortcut.
 *
 * <p>Resolution is deterministic and fails at registry-build time, not at first use (NFR-R1): a
 * factory declaring a blank discriminator, and two factories claiming the same one, each abort
 * startup with an error naming the port and the offending providers.
 *
 * <p>Implements FR2, FR5, NFR-R1 of add-plugin-architecture.
 */
final class CheckClientDiscovery {

    /** The port name used in the fail-fast messages, so an operator sees which registry broke. */
    private static final String PORT = "check";

    private CheckClientDiscovery() {}

    /**
     * Discovers every {@link CheckClientFactory} visible to this class's own class loader — the
     * loader that also carries the bundled providers and any plugin jar placed beside them.
     *
     * @return the discovered providers keyed by discriminator; never null
     */
    static Map<String, CheckClientFactory> discover() {
        return discover(CheckClientFactory.class.getClassLoader());
    }

    /**
     * The class-loader-explicit form, so a spec can stage a plugin's {@code META-INF/services} entry
     * on a loader of its own and prove that a jar the core names nowhere still becomes selectable
     * (FR5).
     *
     * @param loader the loader whose service entries are scanned; never null
     * @return the discovered providers keyed by discriminator; never null
     */
    static Map<String, CheckClientFactory> discover(ClassLoader loader) {
        return index(ServiceLoader.load(CheckClientFactory.class, loader));
    }

    /**
     * The pure half of {@link #discover()}, over an already-loaded set of factories, so the
     * fail-fast rules are exercised without staging jars on a class path.
     *
     * @param discovered the factories a discovery pass produced, in encounter order; never null
     * @return the providers keyed by discriminator; never null
     * @throws IllegalStateException if a factory declares a blank discriminator, or two factories
     *     claim the same one
     */
    static Map<String, CheckClientFactory> index(Iterable<CheckClientFactory> discovered) {
        Map<String, CheckClientFactory> registry = new LinkedHashMap<>();
        for (CheckClientFactory factory : discovered) {
            String provider = factory.provider();
            if (provider == null || provider.isBlank()) {
                throw new IllegalStateException("discovered " + PORT + " provider "
                        + factory.getClass().getName() + " declares no provider() discriminator");
            }
            CheckClientFactory previous = registry.put(provider, factory);
            if (previous != null) {
                throw new IllegalStateException("duplicate " + PORT + " provider '" + provider + "' declared by "
                        + previous.getClass().getName() + " and "
                        + factory.getClass().getName());
            }
        }
        return Map.copyOf(registry);
    }
}
