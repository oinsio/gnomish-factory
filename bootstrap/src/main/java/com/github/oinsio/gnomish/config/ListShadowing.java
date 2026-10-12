package com.github.oinsio.gnomish.config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

/**
 * Makes a list set by a higher source replace the same list of every lower source whole (FR5,
 * finding F106 of tighten-spec-contract). Spring binds a typed list from the first source that
 * sets it, but inside a raw subtree such as {@code factory.connections.<name>} it merges indexed
 * keys index by index across sources: a project file's {@code scopes: [project-a]} over a host
 * file's {@code [host-a, host-b, host-c]} would bind {@code {0: project-a, 1: host-b, 2: host-c}}.
 *
 * <p>The list a {@code factory.*} key belongs to is its name up to its first numeric index
 * ({@code factory.connections.acme.scopes} for {@code ...scopes[1]}, or for a YAML sequence that
 * Spring flattened to the same indexed keys); a key with no numeric index names itself, so a
 * comma-separated scalar {@code ...scopes=a,b} on the command line claims the list as well. A
 * lower source's key is hidden whenever a higher source sets any key of the same list. Names are
 * compared in Spring's canonical form, so every spelling the binder accepts is one list. Map
 * entries are separate names, so maps keep merging key by key. A built-in default lives in code
 * and is replaced by any bound list already.
 *
 * <p>Implements FR5 of add-project-registry; F106 of tighten-spec-contract.
 */
public final class ListShadowing {

    private static final ConfigurationPropertyName FACTORY = ConfigurationPropertyName.of("factory");

    private ListShadowing() {}

    /**
     * Hides, in every source, the {@code factory.*} keys of a list some higher source sets — the
     * sources visited in precedence order, a source with nothing hidden left as it is.
     */
    public static void apply(MutablePropertySources sources) {
        Set<ConfigurationPropertyName> higher = new HashSet<>();
        for (PropertySource<?> source : sources.stream().toList()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            Set<String> hidden = new HashSet<>();
            Set<ConfigurationPropertyName> own = new HashSet<>();
            for (String key : enumerable.getPropertyNames()) {
                ConfigurationPropertyName list = list(key);
                if (list == null) {
                    continue;
                }
                if (higher.contains(list)) {
                    hidden.add(key);
                } else {
                    own.add(list);
                }
            }
            higher.addAll(own);
            if (!hidden.isEmpty()) {
                sources.replace(source.getName(), new Shadowed(enumerable, hidden));
            }
        }
    }

    /** The list {@code key} belongs to, or {@code null} when it is no {@code factory.*} key. */
    private static @Nullable ConfigurationPropertyName list(String key) {
        ConfigurationPropertyName name = ConfigurationPropertyName.adapt(key, '.');
        if (!FACTORY.isAncestorOf(name)) {
            return null;
        }
        return IntStream.range(0, name.getNumberOfElements())
                .filter(name::isNumericIndex)
                .mapToObj(name::chop)
                .findFirst()
                .orElse(name);
    }

    /** A source with some of its keys hidden; everything else, origins included, is the original's. */
    private static final class Shadowed extends EnumerablePropertySource<EnumerablePropertySource<?>>
            implements OriginLookup<String> {

        private final Set<String> hidden;

        Shadowed(EnumerablePropertySource<?> original, Set<String> hidden) {
            super(original.getName(), original);
            this.hidden = hidden;
        }

        @Override
        public String[] getPropertyNames() {
            return Arrays.stream(source.getPropertyNames())
                    .filter(key -> !hidden.contains(key))
                    .toArray(String[]::new);
        }

        @Override
        public @Nullable Object getProperty(String name) {
            return hidden.contains(name) ? null : source.getProperty(name);
        }

        @Override
        public @Nullable Origin getOrigin(String key) {
            return hidden.contains(key) ? null : OriginLookup.getOrigin(source, key);
        }
    }
}
