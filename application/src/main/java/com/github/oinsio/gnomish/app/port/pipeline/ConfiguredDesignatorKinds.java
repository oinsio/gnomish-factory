package com.github.oinsio.gnomish.app.port.pipeline;

import com.github.oinsio.gnomish.app.TrackerAdapterFactory;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.util.Map;
import java.util.Set;

/**
 * Which designator kinds the tracker adapter a {@code tracker:} section names is configured to
 * extract (FR3, design D5 of add-base-ref-resolution) — the adapter-side half of the startup check
 * that a rule extracting kind {@code base} has a non-empty {@code task-branch.base.allowed} list to
 * grade against.
 *
 * <p>A function rather than a set, because the answer depends on the very configuration being
 * loaded: the adapter is chosen by {@code tracker.type}, and its rules live in the {@code
 * tracker.<type>} subsection, so the loader has to ask once it has mapped that section. The
 * composition root answers from its adapter registry ({@link #fromRegistry}); a caller with no
 * tracker in play answers {@link #NONE}.
 *
 * <p>Implements FR3 of add-base-ref-resolution.
 */
@FunctionalInterface
public interface ConfiguredDesignatorKinds {

    /** No adapter extracts anything: the answer where no tracker registry is in play. */
    @SuppressWarnings("unused")
    ConfiguredDesignatorKinds NONE = config -> Set.of();

    /**
     * The kinds the adapter for {@code config.type()} extracts under {@code config}.
     *
     * @param config the mapped {@code tracker} section; never null
     * @return the extracted kinds; never null, empty when none
     */
    Set<String> forTracker(TrackerConfig config);

    /**
     * Answers from the registered adapter factories: the factory keyed by {@code tracker.type}
     * reports its kinds, and an unregistered type — already a located load error of its own — reports
     * none.
     *
     * @param registry known tracker adapter factories, keyed by {@code tracker.type}; never null
     * @return the registry-backed answer; never null
     */
    static ConfiguredDesignatorKinds fromRegistry(Map<String, TrackerAdapterFactory> registry) {
        return config -> {
            TrackerAdapterFactory factory = registry.get(config.type());
            return factory == null ? Set.of() : factory.configuredDesignatorKinds(config);
        };
    }
}
