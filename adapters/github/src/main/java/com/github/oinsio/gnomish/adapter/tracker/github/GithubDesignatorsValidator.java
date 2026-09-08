package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates the {@code tracker.github.designators} map (FR3 of add-base-ref-resolution, design D5):
 * an optional map of designator kind → regular expression, applied to the issue's label names to
 * derive that kind's candidate values. Kinds are open names — {@code base} is the first the factory
 * consumes and {@code type} the next — so the key is graded only for being a non-blank name; the
 * value is graded for compiling and for carrying <em>exactly one</em> capture group, since the
 * adapter takes that group as the value and has nowhere to put a second one.
 *
 * <p>Errors are located and aggregated exactly like {@link GithubLabelsValidator}'s, so a malformed
 * rule is one located line at load rather than a mid-{@code take} adapter failure (UX1).
 *
 * <p>Implements FR3 of add-base-ref-resolution.
 */
final class GithubDesignatorsValidator {

    private GithubDesignatorsValidator() {}

    /**
     * Validates one {@code designators} map value.
     *
     * @param file the offending file
     * @param where the located field prefix for the map (e.g. {@code tracker.github.designators})
     * @param designators the raw {@code designators} value, expected to be a map
     * @return every located problem in the map; empty when valid
     */
    static List<ConfigError> validate(String file, String where, Object designators) {
        if (!(designators instanceof Map<?, ?> raw)) {
            return List.of(
                    new ConfigError(file, where, "must be an object mapping designator kinds to regular expressions"));
        }
        List<ConfigError> errors = new ArrayList<>();
        for (Map.Entry<String, Object> entry : new TreeMap<>(stringKeyed(raw)).entrySet()) {
            String kind = entry.getKey();
            if (kind.isBlank()) {
                errors.add(new ConfigError(file, where, "designator kind names must not be blank"));
                continue;
            }
            validateRule(file, where + "." + kind, entry.getValue(), errors);
        }
        return List.copyOf(errors);
    }

    private static void validateRule(String file, String where, Object value, List<ConfigError> errors) {
        if (!(value instanceof String rule) || rule.isBlank()) {
            errors.add(new ConfigError(file, where, "must be a regular expression with exactly one capture group"));
            return;
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(rule);
        } catch (PatternSyntaxException e) {
            errors.add(new ConfigError(
                    file, where, "'%s' is not a valid regular expression: %s".formatted(rule, e.getDescription())));
            return;
        }
        int groups = pattern.matcher("").groupCount();
        if (groups != 1) {
            errors.add(new ConfigError(
                    file,
                    where,
                    ("'%s' must contain exactly one capture group — the designator value — but has %d; "
                                    + "make the extra groups non-capturing with '(?:...)'")
                            .formatted(rule, groups)));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringKeyed(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }
}
