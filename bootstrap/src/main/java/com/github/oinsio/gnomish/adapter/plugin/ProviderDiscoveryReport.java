package com.github.oinsio.gnomish.adapter.plugin;

import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports the discovered provider set of one port at startup, naming the artifact that contributed
 * each provider (NFR-O1, design D6 of add-plugin-architecture).
 *
 * <p>This is the observability half of the trust posture. A discovered jar runs inside the
 * privileged factory process with credential access, so the posture is "only trusted jars go on the
 * classpath" — an operator responsibility, documented in {@code gnomish-plugin-api/README.md}
 * (NFR-S3). What the factory owes in return is visibility: the provider set of every port, with the
 * jar behind each entry, printed before any task runs, so a provider nobody meant to install is
 * seen at startup rather than inferred from a task's behaviour.
 *
 * <p>The artifact is read from the provider class's own {@link CodeSource}, which is the honest
 * answer to "which jar contributed this": it is where the class was actually loaded from, not what
 * some manifest claims. A packaged distribution reports jar file names; a development classpath
 * reports the classes directory, which identifies the module just as well.
 *
 * <p>Lives beside the two port registries in {@code :bootstrap} because building them is
 * composition, and takes {@code Map<String, ?>} rather than either port's SPI type so it stays one
 * report shared by both ports and depends on neither {@code adapter.tracker} nor
 * {@code adapter.check}.
 *
 * <p>Implements NFR-O1, NFR-S3 of add-plugin-architecture.
 */
public final class ProviderDiscoveryReport {

    /** Reported when a class's origin cannot be determined — a boot-classpath or generated class. */
    static final String UNKNOWN_ARTIFACT = "unknown";

    private static final Logger LOG = LoggerFactory.getLogger(ProviderDiscoveryReport.class);

    private static final String JAR_SUFFIX = ".jar";

    private ProviderDiscoveryReport() {}

    /**
     * Logs the report for one port and hands the registry straight back, so a registry-building
     * bean reports by wrapping its own result rather than by growing a second statement.
     *
     * @param port the port name the registry belongs to, as an operator reads it; never null
     * @param registry the discovered providers keyed by discriminator; never null
     * @param <T> the port's SPI factory type
     * @return the same registry instance
     */
    public static <T> Map<String, T> reported(String port, Map<String, T> registry) {
        render(port, registry).forEach(LOG::info);
        return registry;
    }

    /**
     * Renders the report as lines, so what an operator sees is asserted directly rather than
     * through a logger.
     *
     * @param port the port name the registry belongs to; never null
     * @param registry the discovered providers keyed by discriminator; never null
     * @return the report lines, one header plus one line per provider; never null
     */
    static List<String> render(String port, Map<String, ?> registry) {
        if (registry.isEmpty()) {
            return List.of("no " + port + " providers discovered");
        }
        List<String> lines = new ArrayList<>();
        lines.add("discovered " + registry.size() + " " + port + " provider(s):");
        registry.forEach((discriminator, provider) -> lines.add("  " + discriminator + " <- "
                + artifactOf(provider.getClass()) + " (" + provider.getClass().getName() + ")"));
        return List.copyOf(lines);
    }

    /**
     * The artifact a class was loaded from.
     *
     * @param providerType the discovered provider's own class; never null
     * @return the jar file name, the classes directory, or {@link #UNKNOWN_ARTIFACT}; never null
     */
    static String artifactOf(Class<?> providerType) {
        return artifactOfSource(providerType.getProtectionDomain().getCodeSource());
    }

    /**
     * The {@link CodeSource}-taking half, so the two origin-less cases are exercised directly:
     * a class with no code source at all, and one whose code source carries no location.
     *
     * @param source the class's code source; null when the class has none
     * @return the jar file name, the classes directory, or {@link #UNKNOWN_ARTIFACT}; never null
     */
    static String artifactOfSource(@Nullable CodeSource source) {
        if (source == null || source.getLocation() == null) {
            return UNKNOWN_ARTIFACT;
        }
        String path = source.getLocation().getPath();
        if (path.endsWith(JAR_SUFFIX)) {
            return path.substring(path.lastIndexOf('/') + 1);
        }
        return path;
    }
}
