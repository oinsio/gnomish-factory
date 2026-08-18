package com.github.oinsio.gnomish.adapter.github;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The one connection key both github providers read the same way: {@code credential}, naming the
 * environment variable their token is resolved from (FR16, FR17, design D8/D11 of
 * add-plugin-architecture).
 *
 * <p>It exists because a named connection profile may rename a vendor's credential — one operator
 * runs two github connections under two tokens — and a compile-time constant cannot see a name that
 * arrives as configuration data. Absent the key, each provider keeps its historical default
 * ({@code GNOMISH_GITHUB_TOKEN} / {@code GNOMISH_GITHUB_ACTIONS_TOKEN}), so an inline subsection
 * that never heard of profiles behaves exactly as before.
 *
 * <p>The key carries a credential <em>name</em>, never a value (NFR-S1): the value is resolved
 * through the {@code SecretsProvider} at construction, and the resolved name joins the run's scrub /
 * never-allowlist set through the SPI's credential declaration.
 *
 * <p>Implements FR16, FR17 of add-plugin-architecture.
 */
public final class GithubCredential {

    /** The connection key naming this provider's credential environment variable. */
    public static final String KEY = "credential";

    /**
     * Key fragments that mark a config key as credential-shaped: matched case-insensitively against
     * a normalized (hyphens/underscores stripped) form of each subsection key, so {@code token},
     * {@code api-token}, {@code apiToken}, and {@code access-token} are all caught alike.
     */
    private static final Set<String> TOKEN_KEY_FRAGMENTS = Set.of("token");

    private GithubCredential() {}

    /**
     * The credential environment variable name a resolved connection declares, or {@code
     * defaultName} when it declares none.
     *
     * @param connection the provider's connection data, already profile-resolved; never null
     * @param defaultName the provider's historical constant, used when the key is absent
     * @return the credential name to resolve the token under; never null
     */
    public static String nameOr(Map<String, Object> connection, String defaultName) {
        return connection.get(KEY) instanceof String name && !name.isBlank() ? name : defaultName;
    }

    /**
     * Grades the key's shape: present but not a non-blank string is a configuration mistake, since a
     * blank environment variable name resolves nothing and would fail closed only at first use.
     *
     * @param subsection the raw subsection, already profile-resolved; never null
     * @param file the offending configuration file
     * @param where the located field prefix of the subsection
     * @return the located problem, or empty when the key is absent or well-formed
     */
    public static List<ConfigError> validateName(Map<String, Object> subsection, String file, String where) {
        if (!subsection.containsKey(KEY) || (subsection.get(KEY) instanceof String name && !name.isBlank())) {
            return List.of();
        }
        return List.of(new ConfigError(
                file,
                where + "." + KEY,
                "'%s' must be a non-blank credential environment variable name".formatted(KEY)));
    }

    /**
     * Rejects every credential-shaped key in a subsection (NFR-S1): both github providers name their
     * credential, never carry one, so a {@code token}-shaped key is a located error rather than an
     * ignored extra. Shared so the tracker and check subsections apply one normalization rule.
     *
     * @param subsection the raw subsection, already profile-resolved; never null
     * @param file the offending configuration file
     * @param where the located field prefix of the subsection
     * @param scope where the key was written, as the message names it (e.g. {@code config.yaml})
     * @param envVar the provider's credential environment variable, named in the message
     * @return one located problem per credential-shaped key; empty when the subsection carries none
     */
    public static List<ConfigError> rejectTokenKeys(
            Map<String, Object> subsection, String file, String where, String scope, String envVar) {
        List<ConfigError> errors = new ArrayList<>();
        for (String key : subsection.keySet()) {
            String normalized = key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
            if (TOKEN_KEY_FRAGMENTS.stream().anyMatch(normalized::contains)) {
                errors.add(new ConfigError(
                        file,
                        where + "." + key,
                        "'%s' must not appear in %s; %s is read from the environment only"
                                .formatted(key, scope, envVar)));
            }
        }
        return List.copyOf(errors);
    }
}
