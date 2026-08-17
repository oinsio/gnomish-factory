package com.github.oinsio.gnomish.adapter.github;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.List;
import java.util.Map;

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
}
