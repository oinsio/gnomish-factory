package com.github.oinsio.gnomish.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.Resource;

/**
 * The operator configuration sources and their checks (FR5, FR6, FR7): the {@code factory:} block
 * of an operator file as a property source that keeps each value's file and line; the check of
 * every property source Spring assembled — the command line, the JVM system properties, the
 * environment, and anything else such as an {@code application.yaml} beside the jar — against the
 * level table; and the placement of the two files below the command line, the project file above
 * the host file.
 *
 * <p>Implements FR5, FR6, FR7, NFR-S1, NFR-S2 of add-project-registry.
 */
public final class OperatorSources {

    private static final String PREFIX = "factory.";
    private static final String VARIABLE_PREFIX = "FACTORY_";
    private static final Set<PosixFilePermission> OTHERS_WRITE =
            Set.of(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE);

    private OperatorSources() {}

    /**
     * The {@code factory.*} keys of an operator file's document as one source, each checked
     * against {@code where}'s levels; any other key the file's own format does not claim is
     * reported as foreign.
     *
     * @param name the source's name in the environment
     * @param document the file's property sources, as {@code OperatorFile.parse} yields them
     * @param owned the file's own non-configuration keys, e.g. a project file's {@code clones.*}
     * @param holds what the file holds, for the foreign-key line
     * @return the source, or {@code null} when the file sets no {@code factory.*} key
     */
    public static @Nullable PropertySource<?> factoryBlock(
            String name,
            List<PropertySource<?>> document,
            SettingSource where,
            Predicate<String> owned,
            String holds,
            ConfigViolations violations) {
        Map<String, Object> block = new LinkedHashMap<>();
        for (PropertySource<?> part : document) {
            Map<?, ?> values = (Map<?, ?>) part.getSource();
            for (String key : ((EnumerablePropertySource<?>) part).getPropertyNames()) {
                if (key.startsWith(PREFIX)) {
                    violations.key(where, key, location(part, key));
                    block.put(key, values.get(key));
                } else if (!owned.test(key)) {
                    violations.foreign(key, location(part, key), holds);
                }
            }
        }
        return block.isEmpty() ? null : new OriginTrackedMapPropertySource(name, block, true);
    }

    /**
     * Checks every source Spring assembled: {@code factory.*} keys on the command line and in
     * system properties against the command line's levels, every {@code FACTORY_*} variable, and
     * any {@code factory.*} key in any other source.
     */
    public static void check(ConfigurableEnvironment environment, ConfigViolations violations) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String key : enumerable.getPropertyNames()) {
                if (source.getName().equals(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
                    if (key.toUpperCase(Locale.ROOT).startsWith(VARIABLE_PREFIX)) {
                        violations.variable(key, String.valueOf(source.getProperty(key)));
                    }
                } else if (key.startsWith(PREFIX)) {
                    violations.key(sourceOf(source), key, location(source, key));
                }
            }
        }
    }

    /** Adds a file in {@code files} to {@code violations} when group or others may write it (NFR-S2). */
    public static void checkWritable(List<Path> files, ConfigViolations violations) {
        for (Path file : files) {
            PosixFileAttributeView view =
                    Files.exists(file) ? Files.getFileAttributeView(file, PosixFileAttributeView.class) : null;
            // a file system with no POSIX view has no group or others to check
            if (view != null && othersMayWrite(view)) {
                violations.writable(file);
            }
        }
    }

    /**
     * Places the project source above the host source, both below the command line and the JVM
     * system properties (FR5).
     */
    public static void add(
            ConfigurableEnvironment environment,
            @Nullable PropertySource<?> project,
            @Nullable PropertySource<?> host) {
        MutablePropertySources sources = environment.getPropertySources();
        String above = sources.contains(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
                ? StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME
                : CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME;
        above = place(sources, above, project);
        place(sources, above, host);
    }

    /** Adds {@code source} just below {@code above} (first when absent); returns the new anchor. */
    private static String place(MutablePropertySources sources, String above, @Nullable PropertySource<?> source) {
        if (source == null) {
            return above;
        }
        if (sources.contains(above)) {
            sources.addAfter(above, source);
        } else {
            sources.addFirst(source);
        }
        return source.getName();
    }

    private static SettingSource sourceOf(PropertySource<?> source) {
        return source instanceof CommandLinePropertySource<?>
                        || source.getName().equals(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
                ? SettingSource.COMMAND_LINE
                : SettingSource.OTHER;
    }

    /**
     * Where a key was found: file and line, the command-line form, or — for a source that tracks no
     * text origin — the source's name.
     */
    private static String location(PropertySource<?> source, String key) {
        if (source instanceof CommandLinePropertySource<?>) {
            return "the command line (--" + key + ")";
        }
        if (source.getName().equals(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)) {
            return "the command line (-D" + key + ")";
        }
        Origin origin = OriginLookup.getOrigin(source, key);
        if (origin instanceof TextResourceOrigin text) {
            Resource resource = text.getResource();
            TextResourceOrigin.Location line = text.getLocation();
            if (resource != null && line != null) {
                return resource.getDescription() + ":" + (line.getLine() + 1);
            }
        }
        return source.getName();
    }

    private static boolean othersMayWrite(PosixFileAttributeView view) {
        try {
            return view.readAttributes().permissions().stream().anyMatch(OTHERS_WRITE::contains);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
