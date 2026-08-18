package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.app.ConnectionProfiles;
import com.github.oinsio.gnomish.app.TrackerSubsectionValidator;
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
 *   <li>the section is present but {@code type} is omitted — missing type; the
 *       sole reported problem, since no subsection can be judged without a type
 *       to match it against;</li>
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
        return validate(file, tracker, registry, ConnectionProfiles.none());
    }

    /**
     * The connection-aware form (FR16, design D8/D12 of add-plugin-architecture): identical, except
     * that the adapter subsection may reference a named operator-declared connection profile as
     * {@code connection: <name>} instead of inlining its endpoint and credential-name keys. The
     * profiles live in operator configuration while this subsection is repo-side, so the composition
     * root hands the defined set down here as plain data — an undefined reference is then a located
     * load error alongside every other one, never a mid-{@code take} failure.
     *
     * @param profiles the operator-declared {@code factory.connections} profiles; never null
     */
    public static List<ConfigError> validate(
            String file,
            @Nullable TrackerDto tracker,
            Map<String, TrackerSubsectionValidator> registry,
            ConnectionProfiles profiles) {
        if (tracker == null) {
            return List.of();
        }
        String type = tracker.type();
        if (type == null) {
            return List.of(new ConfigError(file, "tracker.type", "missing required tracker type"));
        }
        List<ConfigError> errors = new ArrayList<>();
        TrackerSubsectionValidator validator = registry.get(type);
        if (validator == null) {
            errors.add(new ConfigError(file, "tracker.type", "unknown tracker type '%s'".formatted(type)));
        }
        checkSubsections(file, type, validator, tracker.subsections(), profiles, errors);
        return List.copyOf(errors);
    }

    /** Checks the matching subsection (missing, or delegated) and reports every stray one, in name order. */
    private static void checkSubsections(
            String file,
            String type,
            @Nullable TrackerSubsectionValidator validator,
            Map<String, Object> subsections,
            ConnectionProfiles profiles,
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
            if (entry.getValue() instanceof Map<?, ?> raw) {
                Map<String, Object> subsection = PipelineMapper.castSubsection(raw);
                // The `connection:` reference is the one key core owns here (FR16): graded even for
                // an adapter that contributes no validator of its own, since an undefined profile is
                // a seam problem, not adapter-owned content.
                errors.addAll(profiles.validateReference(file, where, subsection));
                if (validator != null) {
                    errors.addAll(validator.validate(file, where, subsection, profiles));
                }
            }
        }
        if (validator != null && !subsections.containsKey(type)) {
            errors.add(new ConfigError(file, "tracker." + type, "missing required subsection '%s'".formatted(type)));
        }
    }
}
