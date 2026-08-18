package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.adapter.github.GithubCredential;
import com.github.oinsio.gnomish.app.TrackerSubsectionValidator;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * {@code GNOMISH_GITHUB_TOKEN} — or the variable {@code credential} names when
 * a connection profile renames it, FR16 of add-plugin-architecture — read at
 * adapter construction, never yaml).
 *
 * <p>This class only checks subsection content; it does not read the
 * environment, build the API client, or apply label defaults — those are
 * later tasks (4.4, 4.7).
 *
 * <p>Implements FR17, NFR-S1 of add-tracker-port.
 */
public final class GithubTrackerSubsectionValidator implements TrackerSubsectionValidator {

    @Override
    public List<ConfigError> validate(String file, String where, Map<String, Object> subsection) {
        List<ConfigError> errors = new ArrayList<>();
        requireNonBlankString(subsection, "api-url", file, where, errors);
        requireNonBlankString(subsection, "repo", file, where, errors);
        errors.addAll(GithubCredential.rejectTokenKeys(
                subsection, file, where, "config.yaml", GithubTrackerAdapterFactory.TOKEN_ENV_VAR));
        errors.addAll(GithubCredential.validateName(subsection, file, where));
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
}
