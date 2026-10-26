package com.github.oinsio.gnomish.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Every configuration violation the configuration loader found in one run, each one line naming
 * its location, the key, the reason and the fix (UX1 of add-project-registry) — the one decision
 * behind both of its forms (design D1 of add-doctor-command): every subcommand but {@code doctor}
 * refuses on a non-empty value with {@link #exception()}; {@code doctor} reads the value, published
 * as a bean, and lists each line as a check result instead of stopping.
 *
 * <p>Implements FR7 of add-doctor-command.
 *
 * @param lines one line per violation, in the order found; empty when the configuration is clean
 */
public record ConfigurationViolations(List<String> lines) {

    /** A configuration with no violation. */
    public static final ConfigurationViolations NONE = new ConfigurationViolations(List.of());

    public ConfigurationViolations {
        lines = List.copyOf(lines);
    }

    /** Whether nothing was found. */
    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /** These lines followed by {@code later}'s, in the order found. */
    public ConfigurationViolations plus(ConfigurationViolations later) {
        List<String> all = new ArrayList<>(lines);
        all.addAll(later.lines());
        return new ConfigurationViolations(all);
    }

    /**
     * The refusing form: the report every subcommand but {@code doctor} stops on.
     *
     * @throws IllegalArgumentException if nothing was found
     */
    public ConfigurationViolationsException exception() {
        return new ConfigurationViolationsException(lines);
    }
}
