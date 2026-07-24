package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Validates the seam around the adapter-owned {@code tracker} subsection
 * (FR17 of add-tracker-port, design D5): the core loader knows only the two
 * shared keys ({@code type}, {@code abort-threshold}, task 3.1) and delegates
 * the typed subsection's schema to a registered {@link
 * TrackerSubsectionValidator} — but the loader itself still owns and reports
 * every problem around that delegation, located and aggregated like every
 * other {@link ConfigError}:
 *
 * <ul>
 *   <li>{@code type} names no registered adapter — unknown type;</li>
 *   <li>{@code type} is known but its matching subsection (named after {@code
 *       type}) is absent — missing subsection;</li>
 *   <li>a subsection is present whose key does not match {@code type} — a
 *       stray subsection, never silently ignored, even when the matching one
 *       is also present and valid.</li>
 * </ul>
 *
 * <p>When the subsection matches and a validator is registered for {@code
 * type}, its content is handed to that validator unmodified; any errors it
 * returns are threaded straight into the same result list, so adapter and
 * core errors aggregate in one pass under the loader's single-pass contract
 * (task 6.5, NFR-R1).
 *
 * <p>No registry is wired with real adapters yet (the GitHub adapter is task
 * 4; Spring-based registration is task 5.15) — callers with no known adapters
 * pass an empty registry, under which every {@code type} is reported unknown.
 *
 * <p>Implements FR17 of add-tracker-port.
 */
public final class TrackerSeamValidator {

    private TrackerSeamValidator() {}

    /**
     * Validates the tracker seam for one {@code config.yaml}.
     *
     * <p>Implements FR17 of add-tracker-port.
     *
     * @param file the offending file, always {@code "config.yaml"}
     * @param tracker the parsed {@code tracker} DTO, or {@code null} when the
     *     whole section is absent (nothing to check)
     * @param registry known adapter validators, keyed by their {@code type}
     *     name; an adapter with no registered entry is reported unknown
     * @return every located seam and delegated adapter error, in
     *     type-then-subsection order; empty when the tracker section is
     *     absent or fully valid
     */
    public static List<ConfigError> validate(
            String file, @Nullable TrackerDto tracker, Map<String, TrackerSubsectionValidator> registry) {
        if (tracker == null) {
            return List.of();
        }
        List<ConfigError> errors = new ArrayList<>();
        String type = tracker.type();
        TrackerSubsectionValidator validator = type == null ? null : registry.get(type);
        if (type != null && validator == null) {
            errors.add(new ConfigError(file, "tracker.type", "unknown tracker type '%s'".formatted(type)));
        }
        checkSubsections(file, type, validator, tracker.subsections(), errors);
        return List.copyOf(errors);
    }

    /** Checks the matching subsection (missing, or delegated) and reports every stray one, in name order. */
    private static void checkSubsections(
            String file,
            @Nullable String type,
            @Nullable TrackerSubsectionValidator validator,
            Map<String, Object> subsections,
            List<ConfigError> errors) {
        for (Map.Entry<String, Object> entry : new TreeMap<>(subsections).entrySet()) {
            String name = entry.getKey();
            String where = "tracker." + name;
            if (!name.equals(type)) {
                errors.add(new ConfigError(
                        file,
                        where,
                        "subsection '%s' does not match declared tracker type '%s'".formatted(name, type)));
                continue;
            }
            if (validator != null && entry.getValue() instanceof Map<?, ?> raw) {
                errors.addAll(validator.validate(file, where, castSubsection(raw)));
            }
        }
        if (validator != null && type != null && !subsections.containsKey(type)) {
            errors.add(new ConfigError(file, "tracker." + type, "missing required subsection '%s'".formatted(type)));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castSubsection(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }
}
