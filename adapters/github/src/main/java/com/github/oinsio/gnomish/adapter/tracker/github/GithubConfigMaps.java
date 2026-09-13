package com.github.oinsio.gnomish.adapter.tracker.github;

import java.util.Map;

/**
 * Shared casting helper for {@code tracker.github} yaml-map readers ({@link GithubLabelsValidator},
 * {@link GithubDesignatorsValidator}, {@link GithubTrackerAdapterFactorySupport}): each accepts an
 * already-checked {@code Map<?, ?>} whose keys are known (by the yaml loader's contract) to be
 * strings, and needs a {@code Map<String, Object>} view — the validators to iterate in sorted key
 * order for located errors, the factory support to read the {@code name}/{@code color} overrides.
 */
final class GithubConfigMaps {

    private GithubConfigMaps() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> stringKeyed(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }
}
