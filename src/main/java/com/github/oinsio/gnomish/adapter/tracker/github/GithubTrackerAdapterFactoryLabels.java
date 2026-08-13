package com.github.oinsio.gnomish.adapter.tracker.github;

import java.util.Map;

/**
 * Label-definition defaults and config-driven resolution for {@link GithubTrackerAdapterFactory}
 * (FR5): the four default label defs — names {@code gnomish:ready}/{@code working}/{@code
 * needs-human}/{@code delivered}; colors from GitHub's common palette — {@code 2ea44f}/{@code
 * 1f6feb}/{@code d73a4a}/{@code 8250df}; each with a short operator-hint description — plus the
 * override-from-config lookup and the shared "required string subsection value" validation used
 * when assembling a {@code GithubTracker}.
 *
 * <p>Extracted from {@link GithubTrackerAdapterFactory} for file size; the behavior is unchanged.
 */
final class GithubTrackerAdapterFactoryLabels {

    static final GithubLabelDef DEFAULT_READY =
            new GithubLabelDef("gnomish:ready", "2ea44f", "Gnomish factory: ready to be claimed");
    static final GithubLabelDef DEFAULT_WORKING =
            new GithubLabelDef("gnomish:working", "1f6feb", "Gnomish factory: currently being worked");
    static final GithubLabelDef DEFAULT_NEEDS_HUMAN =
            new GithubLabelDef("gnomish:needs-human", "d73a4a", "Gnomish factory: waiting on a human decision");
    static final GithubLabelDef DEFAULT_DELIVERED =
            new GithubLabelDef("gnomish:delivered", "8250df", "Gnomish factory: delivered for review");

    private GithubTrackerAdapterFactoryLabels() {}

    static String requireStringValue(Map<String, Object> subsection, String key) {
        Object value = subsection.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new GithubTrackerConfigException(
                    "tracker.github." + key + " is required to build the GitHub tracker");
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    static GithubLabelDef resolveLabel(Map<String, Object> subsection, String key, GithubLabelDef fallback) {
        Object labels = subsection.get("labels");
        if (!(labels instanceof Map<?, ?> labelsMap)) {
            return fallback;
        }
        Object entry = labelsMap.get(key);
        if (!(entry instanceof Map<?, ?> raw)) {
            return fallback;
        }
        Map<String, Object> entryMap = (Map<String, Object>) raw;
        String name = (String) entryMap.getOrDefault("name", fallback.name());
        String color = (String) entryMap.getOrDefault("color", fallback.color());
        return new GithubLabelDef(name, color, fallback.description());
    }
}
