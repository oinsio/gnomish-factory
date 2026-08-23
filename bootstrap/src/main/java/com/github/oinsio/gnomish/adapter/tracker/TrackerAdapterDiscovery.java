package com.github.oinsio.gnomish.adapter.tracker;

import com.github.oinsio.gnomish.adapter.plugin.ProviderIndex;
import com.github.oinsio.gnomish.app.TrackerAdapterFactory;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Builds the tracker port's provider registry from one {@link ServiceLoader} pass, keyed by each
 * discovered factory's {@link TrackerAdapterFactory#type()} (design D1 of add-plugin-architecture).
 * A jar carrying a {@code META-INF/services/com.github.oinsio.gnomish.app.TrackerAdapterFactory}
 * entry becomes selectable with no edit to any core source file, and the mechanism is identical for
 * the bundled adapters and for a third-party jar — they differ only in which jar ships the entry
 * (FR1).
 *
 * <p>Resolution is deterministic and fails at registry-build time, not at first use (NFR-R1): a
 * factory declaring a blank discriminator, and two factories claiming the same one, each abort
 * startup with an error naming the port and the offending providers. There is no silent fallback and
 * no arbitrary pick between a colliding pair.
 *
 * <p>Implements FR1, FR2 of add-plugin-architecture; NFR-R1 of add-plugin-architecture.
 */
public final class TrackerAdapterDiscovery {

    /** The port name used in the fail-fast messages, so an operator sees which registry broke. */
    private static final String PORT = "tracker";

    private TrackerAdapterDiscovery() {}

    /**
     * Discovers every {@link TrackerAdapterFactory} visible to this class's own class loader — the
     * loader that also carries the bundled adapters and any plugin jar placed beside them.
     *
     * @return the discovered providers keyed by discriminator; never null
     */
    static Map<String, TrackerAdapterFactory> discover() {
        return discover(TrackerAdapterFactory.class.getClassLoader());
    }

    /**
     * The class-loader-explicit form, so a spec can stage a plugin's {@code META-INF/services} entry
     * on a loader of its own and prove that a jar the core names nowhere still becomes selectable
     * (FR1).
     *
     * @param loader the loader whose service entries are scanned; never null
     * @return the discovered providers keyed by discriminator; never null
     */
    public static Map<String, TrackerAdapterFactory> discover(ClassLoader loader) {
        return index(ServiceLoader.load(TrackerAdapterFactory.class, loader));
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
    static Map<String, TrackerAdapterFactory> index(Iterable<TrackerAdapterFactory> discovered) {
        return ProviderIndex.index(PORT, "type", discovered, TrackerAdapterFactory::type);
    }
}
