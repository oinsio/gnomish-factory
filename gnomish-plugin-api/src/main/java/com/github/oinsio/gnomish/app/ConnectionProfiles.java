package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The named per-vendor connection profiles an operator declares as {@code factory.connections.<name>}
 * (FR16, UX3, design D8 of add-plugin-architecture), and the one engine-defined key that references
 * them from a port subsection: {@code connection: <name>}.
 *
 * <p>One vendor serving two ports — a github tracker and github checks — otherwise duplicates its
 * endpoint and credential name in both subsections, two copies to keep in sync. A profile holds that
 * connection data once; each port references it by name. Provider selection stays per-port: a profile
 * shares connection data, never the provider choice (FR3).
 *
 * <p>Core interprets exactly one key here — {@link #REFERENCE_KEY} — and never a vendor's own. A
 * referencing subsection is {@link #resolve resolved} into the flat, inline-shaped map a provider
 * already knows how to read, so no provider needs profile-specific parsing; what a provider gains is
 * that its credential name can now be configuration data (FR17, design D11).
 *
 * <p>A profile carries a credential <em>name</em>, never a value (NFR-S1).
 *
 * <p>Implements FR16, FR17 of add-plugin-architecture.
 */
public final class ConnectionProfiles {

    /** The engine-defined key a port subsection references a profile by ({@code connection: <name>}). */
    public static final String REFERENCE_KEY = "connection";

    private static final ConnectionProfiles NONE = new ConnectionProfiles(Map.of());

    private final Map<String, Map<String, Object>> profiles;

    private ConnectionProfiles(Map<String, Map<String, Object>> profiles) {
        var copy = new LinkedHashMap<String, Map<String, Object>>();
        profiles.forEach((name, content) -> copy.put(name, content == null ? Map.of() : Map.copyOf(content)));
        this.profiles = Map.copyOf(copy);
    }

    /**
     * The profiles bound from {@code factory.connections}, keyed by name, each carried as raw untyped
     * content only the referencing provider interprets.
     */
    public static ConnectionProfiles of(Map<String, Map<String, Object>> profiles) {
        return profiles.isEmpty() ? NONE : new ConnectionProfiles(profiles);
    }

    /** No profile is defined: every {@code connection:} reference is then an undefined one. */
    public static ConnectionProfiles none() {
        return NONE;
    }

    /** The defined profile names, for a validator reporting an undefined reference. */
    public Set<String> names() {
        return profiles.keySet();
    }

    /**
     * The profile name {@code subsection} references, or empty when it declares its connection
     * inline. A non-{@code String} or blank value is not a reference — {@link #validateReference}
     * reports it as the malformed one it is.
     */
    public static Optional<String> referenceIn(Map<String, Object> subsection) {
        Object value = subsection.get(REFERENCE_KEY);
        if (value instanceof String name && !name.isBlank()) {
            return Optional.of(name);
        }
        return Optional.empty();
    }

    /**
     * The subsection a provider actually reads: an inline one unchanged, or the referenced profile's
     * keys overlaid by the subsection's own, with {@link #REFERENCE_KEY} itself dropped.
     *
     * <p>Deliberately lenient on an undefined name — it resolves to the subsection minus the
     * reference, so a provider then fails on the key it is missing. Every path here validates first
     * ({@link #validateReference} at the load seam), so an undefined reference is always a located
     * error before it can be a runtime one; resolution has no second way to fail.
     */
    public Map<String, Object> resolve(Map<String, Object> subsection) {
        Optional<String> reference = referenceIn(subsection);
        if (reference.isEmpty() && !subsection.containsKey(REFERENCE_KEY)) {
            return subsection;
        }
        var resolved = new LinkedHashMap<String, Object>(profiles.getOrDefault(reference.orElse(""), Map.of()));
        subsection.forEach((key, value) -> {
            if (!REFERENCE_KEY.equals(key)) {
                resolved.put(key, value);
            }
        });
        return Map.copyOf(resolved);
    }

    /**
     * Grades the {@code connection:} reference itself (FR16): a malformed name, a name no profile
     * defines, and the ambiguous case where a subsection declares both the reference and, inline, a
     * key the referenced profile also carries. What a subsection declaring <em>neither</em> form is
     * missing only the provider knows — that stays its own validator's missing-key error.
     *
     * @param file the offending configuration file
     * @param where the located field prefix of this subsection (e.g. {@code factory.check.github})
     * @param subsection the raw subsection, before {@link #resolve}
     * @return every located problem with the reference, in key order; empty when there is none
     */
    public List<ConfigError> validateReference(String file, String where, Map<String, Object> subsection) {
        Object declared = subsection.get(REFERENCE_KEY);
        if (declared == null) {
            return List.of();
        }
        String reference = referenceIn(subsection).orElse("");
        if (reference.isEmpty()) {
            return List.of(
                    new ConfigError(file, where + "." + REFERENCE_KEY, "connection must be a non-blank profile name"));
        }
        Map<String, Object> profile = profiles.get(reference);
        if (profile == null) {
            return List.of(new ConfigError(
                    file,
                    where + "." + REFERENCE_KEY,
                    "undefined connection profile '%s'; defined profiles: %s".formatted(reference, profiles.keySet())));
        }
        return ambiguousKeys(file, where, subsection, reference, profile);
    }

    /** One error per inline key the referenced profile also defines — the both-forms case. */
    private static List<ConfigError> ambiguousKeys(
            String file, String where, Map<String, Object> subsection, String reference, Map<String, Object> profile) {
        List<ConfigError> errors = new ArrayList<>();
        for (String key : new TreeMap<>(subsection).keySet()) {
            if (profile.containsKey(key)) {
                errors.add(new ConfigError(
                        file,
                        where + "." + key,
                        "declares both 'connection: %s' and inline key '%s'; declare exactly one form"
                                .formatted(reference, key)));
            }
        }
        return List.copyOf(errors);
    }
}
