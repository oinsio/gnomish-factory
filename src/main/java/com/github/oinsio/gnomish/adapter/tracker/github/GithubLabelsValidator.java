package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Validates the {@code tracker.github.labels} map (FR17 of add-tracker-port,
 * design D5): the whole map is optional (unconfigured logical states fall
 * back to the adapter's built-in defaults), but every key present must be one
 * of the four logical states, and every configured entry must be a well-formed
 * {@code {name, color}} object with a 6-digit hex color (no leading {@code
 * #}, matching GitHub's label-color API field per the github-tracker spec's
 * defaults example, e.g. {@code 2ea44f}).
 *
 * <p>Implements FR17 of add-tracker-port.
 */
final class GithubLabelsValidator {

    private static final Set<String> KNOWN_KEYS = Set.of("ready", "working", "needs-human", "delivered");
    private static final Pattern HEX_COLOR = Pattern.compile("[0-9a-fA-F]{6}");

    private GithubLabelsValidator() {}

    /**
     * Validates one {@code labels} map value.
     *
     * @param file the offending file
     * @param where the located field prefix for the labels map (e.g. {@code
     *     tracker.github.labels})
     * @param labels the raw {@code labels} value, expected to be a map
     * @return every located problem in the labels map; empty when valid or absent
     */
    static List<ConfigError> validate(String file, String where, Object labels) {
        if (!(labels instanceof Map<?, ?> raw)) {
            return List.of(new ConfigError(file, where, "must be an object mapping label keys to {name, color}"));
        }
        List<ConfigError> errors = new ArrayList<>();
        for (Map.Entry<String, Object> entry : new TreeMap<>(stringKeyed(raw)).entrySet()) {
            String key = entry.getKey();
            String entryWhere = where + "." + key;
            if (!KNOWN_KEYS.contains(key)) {
                errors.add(new ConfigError(
                        file,
                        entryWhere,
                        "unknown label key '%s'; expected one of ready, working, needs-human, delivered"
                                .formatted(key)));
                continue;
            }
            validateEntry(file, entryWhere, entry.getValue(), errors);
        }
        return List.copyOf(errors);
    }

    private static void validateEntry(String file, String where, Object value, List<ConfigError> errors) {
        if (!(value instanceof Map<?, ?> raw)) {
            errors.add(new ConfigError(file, where, "must be an object with 'name' and 'color'"));
            return;
        }
        Map<String, Object> entry = stringKeyed(raw);
        Object name = entry.get("name");
        if (!(name instanceof String s) || s.isBlank()) {
            errors.add(new ConfigError(file, where + ".name", "missing required key 'name'"));
        }
        Object color = entry.get("color");
        if (!(color instanceof String c) || c.isBlank()) {
            errors.add(new ConfigError(file, where + ".color", "missing required key 'color'"));
        } else if (!HEX_COLOR.matcher(c).matches()) {
            errors.add(new ConfigError(
                    file,
                    where + ".color",
                    "'%s' is not a valid 6-digit hex color (e.g. '2ea44f', no leading '#')".formatted(c)));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringKeyed(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }
}
