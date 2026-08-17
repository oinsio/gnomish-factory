package com.github.oinsio.gnomish.adapter.check.github;

import com.github.oinsio.gnomish.adapter.github.GithubCredential;
import com.github.oinsio.gnomish.app.CheckSubsectionValidator;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates the {@code factory.check.github} operator subsection (FR4, FR5, design D12 of
 * add-plugin-architecture): the provider is configured by declaring both connection keys — {@code
 * api-url} and {@code repo} — or the subsection is not declared at all. Exactly one of the two is
 * a configuration mistake, never a silently disabled provider; that both-or-neither rule used to
 * be a throwing constructor on the typed properties record and is now this provider's own located
 * {@link ConfigError}, which is what lets a third-party provider state its own rule the same way.
 *
 * <p>{@code repo} must be {@code owner/name}, and no credential-shaped key may appear at all
 * (NFR-S1): the token comes only from {@code GNOMISH_GITHUB_ACTIONS_TOKEN} — or the variable an
 * optional {@code credential} key names, when a connection profile renames it (FR16 of
 * add-plugin-architecture) — resolved through the {@code SecretsProvider} at wiring time. The
 * subsection names a credential, never carries one.
 *
 * <p>Implements FR4, FR5 of add-plugin-architecture; NFR-S1 of add-external-check-github-actions.
 */
public final class GithubCheckSubsectionValidator implements CheckSubsectionValidator {

    /** The two connection keys; declaring one without the other is the both-or-neither error. */
    private static final List<String> CONNECTION_KEYS = List.of("api-url", "repo");

    /**
     * Key fragments marking a config key as credential-shaped, matched case-insensitively against a
     * normalized (hyphens/underscores stripped) key, mirroring the tracker subsection validator.
     */
    private static final Set<String> TOKEN_KEY_FRAGMENTS = Set.of("token");

    @Override
    public List<ConfigError> validate(String file, String where, Map<String, Object> subsection) {
        List<ConfigError> errors = new ArrayList<>();
        for (String key : CONNECTION_KEYS) {
            if (!(subsection.get(key) instanceof String value) || value.isBlank()) {
                errors.add(new ConfigError(
                        file,
                        where + "." + key,
                        "%s requires both api-url and repo (or neither); missing required key '%s'"
                                .formatted(where, key)));
            }
        }
        if (subsection.get("repo") instanceof String repo && !repo.isBlank() && !isOwnerName(repo)) {
            errors.add(new ConfigError(file, where + ".repo", "repo must be 'owner/name', got: '%s'".formatted(repo)));
        }
        rejectTokenKeys(subsection, file, where, errors);
        errors.addAll(GithubCredential.validateName(subsection, file, where));
        return List.copyOf(errors);
    }

    /**
     * Reads a required string key from an already-validated subsection. Package-private because the
     * factory reads exactly the keys this validator has guaranteed to be present and non-blank.
     */
    static String stringValue(Map<String, Object> subsection, String key) {
        Object value = subsection.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("factory.check.github." + key + " is required");
        }
        return s;
    }

    /** True for exactly one non-empty segment on either side of a single {@code /}. */
    private static boolean isOwnerName(String repo) {
        int slash = repo.indexOf('/');
        return slash > 0 && slash == repo.lastIndexOf('/') && slash != repo.length() - 1;
    }

    private static void rejectTokenKeys(
            Map<String, Object> subsection, String file, String where, List<ConfigError> errors) {
        for (String key : subsection.keySet()) {
            String normalized = key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
            if (TOKEN_KEY_FRAGMENTS.stream().anyMatch(normalized::contains)) {
                errors.add(new ConfigError(
                        file,
                        where + "." + key,
                        "'%s' must not appear in operator configuration; %s is read from the environment only"
                                .formatted(key, GithubCheckClientFactory.TOKEN_ENV_VAR)));
            }
        }
    }
}
