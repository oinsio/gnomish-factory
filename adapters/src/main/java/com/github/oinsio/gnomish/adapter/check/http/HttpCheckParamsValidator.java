package com.github.oinsio.gnomish.adapter.check.http;

import com.github.oinsio.gnomish.app.CheckParamsValidator;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@code http} provider's counterpart of {@code GithubCheckParamsValidator} (FR6 of
 * add-plugin-architecture): grades an {@code external} check's {@code params} at the load seam, so a
 * malformed target is a located {@link ConfigError} aggregated with every other load problem rather
 * than an adapter failure discovered mid-stage.
 *
 * <p>This provider carries its whole target in {@code params} — it has no per-vendor connection
 * subsection to fall back on — so the grading is correspondingly thorough: a required absolute
 * {@code url}, a read-shaped method, non-secret headers only, a credential named rather than
 * inlined, and conditions that mean something ({@link HttpConditionValidator}).
 *
 * <p>Rejecting an inline {@code Authorization} header is the NFR-S1 rule stated in this provider's
 * own terms: the manifest is committed to the task branch, so a credential belongs in {@code auth}
 * as a name resolved through {@code SecretsProvider} at request time, never as a literal value.
 *
 * <p>Implements FR6, FR10, FR11, NFR-S1 of add-plugin-architecture.
 */
public final class HttpCheckParamsValidator implements CheckParamsValidator {

    /** Read-shaped methods only: a verification check observes a result, it does not create one. */
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST");

    private static final Set<String> KEYS = Set.of(
            HttpCheckParams.URL_KEY,
            HttpCheckParams.METHOD_KEY,
            HttpCheckParams.HEADERS_KEY,
            HttpCheckParams.AUTH_KEY,
            HttpCheckParams.PASS_WHEN_KEY,
            HttpCheckParams.PENDING_WHEN_KEY);

    private static final Set<String> AUTH_KEYS = Set.of(
            HttpCheckParams.Auth.CREDENTIAL_KEY, HttpCheckParams.Auth.HEADER_KEY, HttpCheckParams.Auth.SCHEME_KEY);

    @Override
    public List<ConfigError> validate(String file, String where, Map<String, Object> params) {
        List<ConfigError> errors = new ArrayList<>();
        for (String key : new TreeSet<>(params.keySet())) {
            if (!KEYS.contains(key)) {
                errors.add(new ConfigError(
                        file,
                        where + "." + key,
                        "unknown param '%s' for check provider 'http'; expected one of %s"
                                .formatted(key, new TreeSet<>(KEYS))));
            }
        }
        validateUrl(file, where, params, errors);
        validateMethod(file, where, params, errors);
        validateHeaders(file, where, params, errors);
        validateInterpolation(file, where, params, errors);
        validateAuth(file, where, params, errors);
        HttpConditionValidator.validate(
                file,
                where + "." + HttpCheckParams.PASS_WHEN_KEY,
                HttpCheckParams.submap(params, HttpCheckParams.PASS_WHEN_KEY),
                false,
                errors);
        Map<String, Object> pendingWhen = HttpCheckParams.submap(params, HttpCheckParams.PENDING_WHEN_KEY);
        if (!pendingWhen.isEmpty()) {
            HttpConditionValidator.validate(
                    file, where + "." + HttpCheckParams.PENDING_WHEN_KEY, pendingWhen, true, errors);
        }
        return List.copyOf(errors);
    }

    /** The one required param: an absolute URL, since nothing else supplies a base for it. */
    private static void validateUrl(String file, String where, Map<String, Object> params, List<ConfigError> errors) {
        String located = where + "." + HttpCheckParams.URL_KEY;
        String url = HttpCheckParams.string(params, HttpCheckParams.URL_KEY);
        if (url == null || url.isBlank()) {
            errors.add(new ConfigError(file, located, "an http check requires a non-blank 'url'"));
            return;
        }
        try {
            // Graded with its ${...} references erased: they are not legal URL characters, so the
            // shape to check is the one the request will take, not the one the manifest wrote.
            if (!new URI(HttpCheckVariables.erase(url)).isAbsolute()) {
                errors.add(new ConfigError(file, located, "must be an absolute URL, got: '%s'".formatted(url)));
            }
        } catch (URISyntaxException e) {
            errors.add(new ConfigError(file, located, "is not a valid URL: " + e.getReason()));
        }
    }

    private static void validateMethod(
            String file, String where, Map<String, Object> params, List<ConfigError> errors) {
        if (!params.containsKey(HttpCheckParams.METHOD_KEY)) {
            return;
        }
        String method = HttpCheckParams.string(params, HttpCheckParams.METHOD_KEY);
        if (method == null || !METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            errors.add(new ConfigError(
                    file,
                    where + "." + HttpCheckParams.METHOD_KEY,
                    "unknown method '%s'; expected one of %s".formatted(method, new TreeSet<>(METHODS))));
        }
    }

    /**
     * Grades every {@code ${...}} reference in the target and the headers against the fixed,
     * engine-defined whitelist (NFR-S2, design D5). This is where a manifest is stopped from
     * smuggling arbitrary values — a secret, a tracker field, anything attacker-controlled — into a
     * URL or a header: the reference never becomes a runtime lookup, it becomes a load error naming
     * the check and the disallowed variable.
     */
    private static void validateInterpolation(
            String file, String where, Map<String, Object> params, List<ConfigError> errors) {
        String url = HttpCheckParams.string(params, HttpCheckParams.URL_KEY);
        if (url != null) {
            report(file, where + "." + HttpCheckParams.URL_KEY, url, errors);
        }
        HttpCheckParams.submap(params, HttpCheckParams.HEADERS_KEY)
                .forEach((key, value) -> report(
                        file, where + "." + HttpCheckParams.HEADERS_KEY + "." + key, String.valueOf(value), errors));
    }

    /** One located error per non-whitelisted variable the text references. */
    private static void report(String file, String located, String text, List<ConfigError> errors) {
        for (String name : HttpCheckVariables.referencesIn(text)) {
            if (!HttpCheckVariables.WHITELIST.contains(name)) {
                errors.add(new ConfigError(
                        file,
                        located,
                        "interpolates '${%s}', which is not an interpolatable variable; allowed: %s"
                                .formatted(name, new TreeSet<>(HttpCheckVariables.WHITELIST))));
            }
        }
    }

    /** Headers are non-secret request shape; the authorization header belongs to {@code auth}. */
    private static void validateHeaders(
            String file, String where, Map<String, Object> params, List<ConfigError> errors) {
        Map<String, Object> headers = HttpCheckParams.submap(params, HttpCheckParams.HEADERS_KEY);
        for (String key : new TreeSet<>(headers.keySet())) {
            if (key.equalsIgnoreCase(HttpCheckParams.Auth.DEFAULT_HEADER)) {
                errors.add(new ConfigError(
                        file,
                        where + "." + HttpCheckParams.HEADERS_KEY + "." + key,
                        ("must not be set as a literal header; name the credential under '%s' instead, so"
                                        + " the manifest carries no secret")
                                .formatted(HttpCheckParams.AUTH_KEY)));
            }
        }
    }

    private static void validateAuth(String file, String where, Map<String, Object> params, List<ConfigError> errors) {
        Map<String, Object> auth = HttpCheckParams.submap(params, HttpCheckParams.AUTH_KEY);
        if (auth.isEmpty()) {
            return;
        }
        String located = where + "." + HttpCheckParams.AUTH_KEY;
        for (String key : new TreeSet<>(auth.keySet())) {
            if (!AUTH_KEYS.contains(key)) {
                errors.add(new ConfigError(
                        file,
                        located + "." + key,
                        "unknown key '%s'; expected one of %s".formatted(key, new TreeSet<>(AUTH_KEYS))));
            }
        }
        String credential = HttpCheckParams.string(auth, HttpCheckParams.Auth.CREDENTIAL_KEY);
        if (credential == null || credential.isBlank()) {
            errors.add(new ConfigError(
                    file,
                    located + "." + HttpCheckParams.Auth.CREDENTIAL_KEY,
                    "an http check's auth requires the non-blank NAME of a credential, resolved through the"
                            + " secrets provider at request time"));
        }
    }
}
