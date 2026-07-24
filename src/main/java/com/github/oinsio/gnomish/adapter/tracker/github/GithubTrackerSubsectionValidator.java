package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.adapter.pipeline.TrackerSubsectionValidator;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates the {@code tracker.github} subsection content (FR17 of
 * add-tracker-port, NFR-S1 of add-tracker-port, design D5, D15): {@code
 * api-url} is mandatory with no code default (the github-tracker spec's
 * "Missing api-url is a load error" scenario), {@code repo} is mandatory,
 * {@code labels} is entirely optional (unconfigured entries fall back to the
 * adapter's built-in defaults — {@code gnomish:ready}/{@code 2ea44f} etc.,
 * per the github-tracker spec's "Logical states map to mutually exclusive
 * labels" requirement) but any configured label entry must be a well-formed
 * {@code {name, color}} object with a valid 6-digit hex color, and no
 * credential-shaped key may appear at all (NFR-S1: the token comes only from
 * {@code GNOMISH_GITHUB_TOKEN}, read at adapter construction, never yaml).
 *
 * <p>This class only checks subsection content; it does not read the
 * environment, build the API client, or apply label defaults — those are
 * later tasks (4.4, 4.7).
 *
 * <p>Implements FR17, NFR-S1 of add-tracker-port.
 */
public final class GithubTrackerSubsectionValidator implements TrackerSubsectionValidator {

    /**
     * Key fragments that mark a config key as credential-shaped: matched
     * case-insensitively against a normalized (hyphens/underscores stripped)
     * form of each subsection key, so {@code token}, {@code api-token},
     * {@code apiToken}, and {@code access-token} are all caught alike.
     */
    private static final Set<String> TOKEN_KEY_FRAGMENTS = Set.of("token");

    @Override
    public List<ConfigError> validate(String file, String where, Map<String, Object> subsection) {
        List<ConfigError> errors = new ArrayList<>();
        requireNonBlankString(subsection, "api-url", file, where, errors);
        requireNonBlankString(subsection, "repo", file, where, errors);
        rejectTokenKeys(subsection, file, where, errors);
        Object labels = subsection.get("labels");
        if (labels != null) {
            errors.addAll(GithubLabelsValidator.validate(file, where + ".labels", labels));
        }
        return List.copyOf(errors);
    }

    private static void requireNonBlankString(
            Map<String, Object> subsection, String key, String file, String where, List<ConfigError> errors) {
        Object value = subsection.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            errors.add(new ConfigError(file, where + "." + key, "missing required key '%s'".formatted(key)));
        }
    }

    private static void rejectTokenKeys(
            Map<String, Object> subsection, String file, String where, List<ConfigError> errors) {
        for (String key : subsection.keySet()) {
            String normalized = key.replace("-", "").replace("_", "").toLowerCase(java.util.Locale.ROOT);
            if (TOKEN_KEY_FRAGMENTS.stream().anyMatch(normalized::contains)) {
                errors.add(new ConfigError(
                        file,
                        where + "." + key,
                        "'%s' must not appear in config.yaml; GNOMISH_GITHUB_TOKEN is read from the environment only"
                                .formatted(key)));
            }
        }
    }
}
