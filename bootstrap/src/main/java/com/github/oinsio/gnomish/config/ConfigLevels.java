package com.github.oinsio.gnomish.config;

import com.github.oinsio.gnomish.FactoryApplication;
import com.github.oinsio.gnomish.operatorconfig.ConfigLevel;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DataObjectPropertyName;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;

/**
 * The key → level table of the operator configuration, derived by reflection from the {@link
 * ConfigLevel} on every component of the {@code @ConfigurationProperties} records — never written
 * by hand, so it cannot drift from the keys Spring binds (FR6, design D4). Key names are the ones
 * Spring binds: the record's prefix, then each component's name in dashed form (or its {@link
 * Name}), descending into nested configuration records; a {@code Map}, collection or array
 * component is levelled whole.
 *
 * <p>The one owner of the table: the configuration loader checks each source against it and
 * {@code project show} prints its level column and the built-in defaults from it.
 *
 * <p>Implements FR4, FR6 of add-project-registry.
 */
public final class ConfigLevels {

    private static final String ROOT_PACKAGE = "com.github.oinsio.gnomish";

    private final List<ConfigKey> keys;

    private ConfigLevels(List<ConfigKey> keys) {
        this.keys = List.copyOf(keys);
    }

    /** The table of the records {@link FactoryApplication} registers. */
    public static ConfigLevels application() {
        return of(List.of(FactoryApplication.class
                .getAnnotation(EnableConfigurationProperties.class)
                .value()));
    }

    /**
     * The table of {@code records}.
     *
     * @param records {@code @ConfigurationProperties} records
     * @throws IllegalArgumentException if one is not such a record, or a key component carries no
     *     level
     */
    public static ConfigLevels of(Collection<Class<?>> records) {
        List<ConfigKey> keys = new ArrayList<>();
        for (Class<?> record : records) {
            if (!record.isAnnotationPresent(ConfigurationProperties.class) || !record.isRecord()) {
                throw new IllegalArgumentException(record.getName() + " is not a @ConfigurationProperties record");
            }
            collect(prefix(record), record, List.of(), keys);
        }
        keys.sort(Comparator.comparing(ConfigKey::toString));
        return new ConfigLevels(keys);
    }

    /** Every key, ordered by name. */
    public List<ConfigKey> keys() {
        return keys;
    }

    /**
     * The key {@code property} binds to, compared as Spring binds (relaxed names; a subtree covers
     * the keys below it).
     *
     * @param property a property name as a source spells it, e.g. {@code factory.serve.slots}
     * @return the key, or empty for a property no record defines
     */
    public Optional<ConfigKey> find(String property) {
        ConfigurationPropertyName name = ConfigurationPropertyName.adapt(property, '.');
        return keys.stream().filter(key -> key.covers(name)).findFirst();
    }

    /**
     * The {@code factory.*} property an environment variable spells under Spring's relaxed
     * binding, so a refusal can name the equivalent file line: {@code FACTORY_SERVE_SLOTS} is
     * {@code factory.serve.slots}, {@code FACTORY_GIT_NETWORK_TIMEOUT} is {@code
     * factory.git-network-timeout}, and a variable below a subtree keeps its tail dotted.
     *
     * @param variable an environment variable's name, e.g. {@code FACTORY_SERVE_SLOTS}
     * @return the property; for a variable no key matches, its lowercased, dotted form
     */
    public String propertyOf(String variable) {
        String upper = variable.toUpperCase(Locale.ROOT);
        for (ConfigKey key : keys) {
            String spelled =
                    key.toString().toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
            if (upper.equals(spelled)) {
                return key.toString();
            }
            if (key.subtree() && upper.startsWith(spelled + "_")) {
                return key + "." + dotted(upper.substring(spelled.length() + 1));
            }
        }
        return dotted(upper);
    }

    private static String dotted(String variable) {
        return variable.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    private static void collect(String prefix, Class<?> record, List<RecordComponent> above, List<ConfigKey> keys) {
        for (RecordComponent component : record.getRecordComponents()) {
            String name = prefix + "." + keyName(record, component);
            List<RecordComponent> path = new ArrayList<>(above);
            path.add(component);
            Class<?> type = component.getType();
            if (isNestedRecord(type)) {
                collect(name, type, path, keys);
                continue;
            }
            ConfigLevel level = component.getAnnotation(ConfigLevel.class);
            if (level == null) {
                throw new IllegalArgumentException(
                        name + " (" + record.getName() + "." + component.getName() + ") declares no @ConfigLevel");
            }
            keys.add(new ConfigKey(ConfigurationPropertyName.of(name), level.value(), isSubtree(type), path));
        }
    }

    /** The prefix a {@code @ConfigurationProperties} record binds under. */
    static String prefix(Class<?> record) {
        ConfigurationProperties properties = record.getAnnotation(ConfigurationProperties.class);
        return properties.prefix().isEmpty() ? properties.value() : properties.prefix();
    }

    /** The component's dashed name, or the {@link Name} its field carries. */
    private static String keyName(Class<?> record, RecordComponent component) {
        try {
            Name renamed = record.getDeclaredField(component.getName()).getAnnotation(Name.class);
            return renamed != null ? renamed.value() : DataObjectPropertyName.toDashedForm(component.getName());
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("record " + record.getName() + " has no field for " + component, e);
        }
    }

    private static boolean isNestedRecord(Class<?> type) {
        return type.isRecord() && type.getName().startsWith(ROOT_PACKAGE);
    }

    private static boolean isSubtree(Class<?> type) {
        return Map.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type) || type.isArray();
    }
}
