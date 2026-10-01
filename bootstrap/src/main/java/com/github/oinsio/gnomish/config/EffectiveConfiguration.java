package com.github.oinsio.gnomish.config;

import com.github.oinsio.gnomish.app.project.FactoryHome;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.origin.Origin;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;

/**
 * Every effective {@code factory.*} value of an environment with where it came from — the answer
 * to "why is this value in effect?" that {@code project show} prints (FR4, NFR-O1). A key set by
 * some source shows the highest source's value and origin (file and line, command line), and the
 * value it overrides one source down; a key no source sets shows its built-in default, read from
 * the record Spring binds. Each row carries the key's level from {@link ConfigLevels}.
 *
 * <p>Implements FR4, NFR-O1 of add-project-registry.
 */
public final class EffectiveConfiguration {

    private static final String PREFIX = "factory.";
    private static final String BUILT_IN = "built-in default";
    private static final String UNKNOWN_LEVEL = "unknown";

    private EffectiveConfiguration() {}

    /**
     * One effective value.
     *
     * @param key the key as the winning source spells it, or the canonical key for a default
     * @param value the effective value
     * @param level the key's level, e.g. {@code sandbox-boundary}; {@code unknown} for a key no
     *     record defines
     * @param origin where the value came from
     * @param overrides the value and origin one source down, or {@code null} when none sets it
     */
    public record Row(
            String key,
            String value,
            String level,
            String origin,
            @Nullable String overrides) {}

    /**
     * The rows of {@code environment}, ordered by key.
     *
     * @param home the factory home; a file under it is named relative to it
     */
    public static List<Row> of(ConfigurableEnvironment environment, ConfigLevels levels, FactoryHome home) {
        Map<ConfigurationPropertyName, List<Setting>> set = settings(environment);
        Map<String, Row> rows = new TreeMap<>();
        set.forEach((_, settings) -> {
            Setting top = settings.getFirst();
            String overrides = settings.size() > 1 ? settings.get(1).describe(home) : null;
            rows.put(top.key(), new Row(top.key(), top.value(), level(levels, top.key()), top.origin(home), overrides));
        });
        Binder binder = Binder.get(environment);
        for (ConfigKey key : levels.keys()) {
            if (set.keySet().stream().anyMatch(key::covers)) {
                continue;
            }
            Object value = key.valueIn(bound(binder, key));
            rows.put(key.toString(), new Row(key.toString(), String.valueOf(value), render(key), BUILT_IN, null));
        }
        return List.copyOf(rows.values());
    }

    /** Every {@code factory.*} property of every enumerable source, highest source first. */
    private static Map<ConfigurationPropertyName, List<Setting>> settings(ConfigurableEnvironment environment) {
        Map<ConfigurationPropertyName, List<Setting>> settings = new LinkedHashMap<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String key : enumerable.getPropertyNames()) {
                if (key.startsWith(PREFIX)) {
                    settings.computeIfAbsent(ConfigurationPropertyName.adapt(key, '.'), _ -> new ArrayList<>())
                            .add(new Setting(key, String.valueOf(source.getProperty(key)), source));
                }
            }
        }
        return settings;
    }

    private static Object bound(Binder binder, ConfigKey key) {
        Class<?> record = key.path().getFirst().getDeclaringRecord();
        return binder.bindOrCreate(ConfigLevels.prefix(record), record);
    }

    private static String level(ConfigLevels levels, String key) {
        return levels.find(key).map(EffectiveConfiguration::render).orElse(UNKNOWN_LEVEL);
    }

    private static String render(ConfigKey key) {
        return ConfigPlaces.label(key.level());
    }

    /** One source's value for one key. */
    private record Setting(String key, String value, PropertySource<?> source) {

        String describe(FactoryHome home) {
            return value + " from " + origin(home);
        }

        /** The origin as {@code project show} words it: a file under the home relative to it. */
        String origin(FactoryHome home) {
            return switch (SettingOrigin.of(source, key)) {
                case SettingOrigin.CommandLine _ -> "command line";
                case SettingOrigin.SystemProperty _ -> "command line (-D)";
                case SettingOrigin.TextLine(Resource resource, int line) -> file(resource, home) + ":" + line;
                case SettingOrigin.Other(Origin origin) -> origin != null ? origin.toString() : source.getName();
            };
        }

        private static String file(@Nullable Resource resource, FactoryHome home) {
            if (resource == null) {
                return "(unknown file)";
            }
            try {
                Path path = resource.getFile().toPath().toAbsolutePath().normalize();
                return path.startsWith(home.root())
                        ? home.root().relativize(path).toString()
                        : path.toString();
            } catch (IOException e) {
                return resource.getDescription();
            }
        }
    }
}
