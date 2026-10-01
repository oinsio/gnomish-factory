package com.github.oinsio.gnomish.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.Resource;

/**
 * Where one property of one source was set — the command line, a JVM system property, a line of a
 * text resource, or anything else — classified once for every reader that names it: the violation
 * report (UX1) and {@code project show} (FR4). Each reader words the classification its own way;
 * the classification itself, including Spring's zero-based line becoming the one-based line an
 * editor shows, lives only here.
 *
 * <p>Implements FR4, FR7, UX1 of add-project-registry.
 */
sealed interface SettingOrigin {

    /** A {@code --key=value} argument. */
    record CommandLine() implements SettingOrigin {}

    /** A {@code -Dkey=value} JVM system property — the command line too, in its other form. */
    record SystemProperty() implements SettingOrigin {}

    /**
     * A line of a text resource such as an operator file.
     *
     * @param resource the resource, or {@code null} when Spring tracked none
     * @param line the one-based line number
     */
    record TextLine(@Nullable Resource resource, int line) implements SettingOrigin {}

    /**
     * A source that tracks no line: the reader falls back to the origin or the source's name.
     *
     * @param origin the origin Spring tracked, or {@code null} when none
     */
    record Other(@Nullable Origin origin) implements SettingOrigin {}

    /** Where {@code key} of {@code source} was set. */
    static SettingOrigin of(PropertySource<?> source, String key) {
        if (source instanceof CommandLinePropertySource<?>) {
            return new CommandLine();
        }
        if (isSystemProperties(source)) {
            return new SystemProperty();
        }
        Origin origin = OriginLookup.getOrigin(source, key);
        if (origin instanceof TextResourceOrigin text) {
            TextResourceOrigin.Location location = text.getLocation();
            if (location != null) {
                return new TextLine(text.getResource(), location.getLine() + 1);
            }
        }
        return new Other(origin);
    }

    /** Whether {@code source} is the command line in either form, {@code --key} or {@code -Dkey}. */
    static boolean isCommandLine(PropertySource<?> source) {
        return source instanceof CommandLinePropertySource<?> || isSystemProperties(source);
    }

    private static boolean isSystemProperties(PropertySource<?> source) {
        return source.getName().equals(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    }
}
