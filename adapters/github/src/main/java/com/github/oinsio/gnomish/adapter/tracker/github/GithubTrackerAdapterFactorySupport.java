package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.adapter.github.GithubCredential;
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.util.List;
import java.util.Map;

/**
 * Subsection reading for {@link GithubTrackerAdapterFactory}: the connection halves every entry
 * point needs (the credential, {@code api-url} as an {@link GithubHttpClient}, {@code repo} as a
 * {@link GithubRepoRef}) plus the label-definition defaults and their config-driven resolution (FR5) —
 * the four default label defs, names {@code gnomish:ready}/{@code working}/{@code
 * needs-human}/{@code delivered}; colors from GitHub's common palette — {@code 2ea44f}/{@code
 * 1f6feb}/{@code d73a4a}/{@code 8250df}; each with a short operator-hint description.
 *
 * <p>Extracted from {@link GithubTrackerAdapterFactory} so that {@code create}, {@code
 * refuseForeignRef} and {@code probeRead} read the same subsection keys in exactly one place — and
 * so that the labels the read probe reports missing are, by construction, the labels {@code create}
 * provisions ({@link #resolveLabels}, design D7 of add-doctor-command).
 */
final class GithubTrackerAdapterFactorySupport {

    private static final GithubLabelDef DEFAULT_READY =
            new GithubLabelDef("gnomish:ready", "2ea44f", "Gnomish factory: ready to be claimed");
    private static final GithubLabelDef DEFAULT_WORKING =
            new GithubLabelDef("gnomish:working", "1f6feb", "Gnomish factory: currently being worked");
    private static final GithubLabelDef DEFAULT_NEEDS_HUMAN =
            new GithubLabelDef("gnomish:needs-human", "d73a4a", "Gnomish factory: waiting on a human decision");
    private static final GithubLabelDef DEFAULT_DELIVERED =
            new GithubLabelDef("gnomish:delivered", "8250df", "Gnomish factory: delivered for review");

    private GithubTrackerAdapterFactorySupport() {}

    /** The four configured label definitions, each its configured value or its FR5 default. */
    record ResolvedLabels(
            GithubLabelDef ready, GithubLabelDef working, GithubLabelDef needsHuman, GithubLabelDef delivered) {

        /** The four in the order startup provisions them. */
        List<GithubLabelDef> all() {
            return List.of(ready, working, needsHuman, delivered);
        }
    }

    /**
     * Resolves the connection's credential — {@code GNOMISH_GITHUB_TOKEN} unless a profile renamed
     * it (FR16) — through the {@link SecretsProvider} (FR18, NFR-S1 of add-sandbox-core), failing
     * closed with a clear {@link GithubTrackerConfigException} when it is absent or blank — the
     * provider's {@link SecretsProvider#find} already treats blank as absent, so there is no silent
     * empty value. The token is never logged.
     */
    static String requireToken(SecretsProvider secrets, TrackerConfig config) {
        String credential = GithubCredential.nameOr(config.subsection(), GithubTrackerAdapterFactory.TOKEN_ENV_VAR);
        return secrets.find(credential)
                .orElseThrow(() -> new GithubTrackerConfigException(
                        credential + " is required to use the GitHub tracker adapter, but is missing or blank"));
    }

    /** The {@code tracker.github.repo} binding, split into its owner/repo halves. */
    static GithubRepoRef requireRepoRef(Map<String, Object> subsection) {
        return GithubRepoRef.parse(requireStringValue(subsection, "repo"));
    }

    /** An HTTP client bound to the configured {@code tracker.github.api-url} and {@code token}. */
    static GithubHttpClient httpClientFor(Map<String, Object> subsection, String token) {
        return new GithubHttpClient(requireStringValue(subsection, "api-url"), token);
    }

    private static String requireStringValue(Map<String, Object> subsection, String key) {
        Object value = subsection.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new GithubTrackerConfigException(
                    "tracker.github." + key + " is required to build the GitHub tracker");
        }
        return s;
    }

    static ResolvedLabels resolveLabels(Map<String, Object> subsection) {
        return new ResolvedLabels(
                resolveLabel(subsection, "ready", DEFAULT_READY),
                resolveLabel(subsection, "working", DEFAULT_WORKING),
                resolveLabel(subsection, "needs-human", DEFAULT_NEEDS_HUMAN),
                resolveLabel(subsection, "delivered", DEFAULT_DELIVERED));
    }

    private static GithubLabelDef resolveLabel(Map<String, Object> subsection, String key, GithubLabelDef fallback) {
        Object labels = subsection.get("labels");
        if (!(labels instanceof Map<?, ?> labelsMap)) {
            return fallback;
        }
        Object entry = labelsMap.get(key);
        if (!(entry instanceof Map<?, ?> raw)) {
            return fallback;
        }
        Map<String, Object> entryMap = GithubConfigMaps.stringKeyed(raw);
        String name = (String) entryMap.getOrDefault("name", fallback.name());
        String color = (String) entryMap.getOrDefault("color", fallback.color());
        return new GithubLabelDef(name, color, fallback.description());
    }
}
