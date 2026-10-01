package com.github.oinsio.gnomish.config;

import com.github.oinsio.gnomish.operatorconfig.Level;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;

/**
 * One {@code factory.*} key a {@code @ConfigurationProperties} record binds, with the level its
 * component declares (FR6, design D4). A {@code Map}-, collection- or array-valued component is a
 * subtree: its level covers every key below it ({@code factory.check.<provider>.*}, {@code
 * factory.sandbox.egress-allowlist[0]}).
 *
 * <p>Implements FR4, FR6 of add-project-registry.
 *
 * @param name the key in Spring's canonical form, e.g. {@code factory.git-network-timeout}
 * @param level the level the component declares
 * @param subtree whether the key covers the keys below it
 * @param path the record components from the properties record down to this key's component
 */
@NullMarked
public record ConfigKey(ConfigurationPropertyName name, Level level, boolean subtree, List<RecordComponent> path) {

    public ConfigKey {
        path = List.copyOf(path);
    }

    /**
     * Whether {@code candidate} is this key — compared as Spring binds, so {@code
     * factory.gitNetworkTimeout} is {@code factory.git-network-timeout} — or, for a subtree, lies
     * below it.
     */
    public boolean covers(ConfigurationPropertyName candidate) {
        return name.equals(candidate) || (subtree && name.isAncestorOf(candidate));
    }

    /**
     * The key's value in a bound properties record — the built-in default when no source set it.
     *
     * @param properties an instance of the record this key's path starts at
     * @return the component's value; {@code null} when the key or a record above it is unset
     */
    public @Nullable Object valueIn(Object properties) {
        Object value = properties;
        for (RecordComponent component : path) {
            if (value == null) {
                return null;
            }
            value = read(component, value);
        }
        return value;
    }

    private static @Nullable Object read(RecordComponent component, Object record) {
        try {
            return component.getAccessor().invoke(record);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("cannot read " + component, e);
        }
    }

    @Override
    public String toString() {
        return name.toString();
    }
}
