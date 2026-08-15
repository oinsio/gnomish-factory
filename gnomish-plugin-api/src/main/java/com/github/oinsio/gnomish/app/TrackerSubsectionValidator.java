package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.List;
import java.util.Map;

/**
 * The adapter-owned validation hook for a {@code tracker} subsection (FR17 of
 * add-tracker-port, design D5): each tracker adapter (GitHub, and later Jira,
 * Redmine, ...) registers one implementation, keyed by its {@code type} name,
 * with the adapter-layer {@code TrackerSeamValidator}. The core loader never interprets the
 * subsection's keys itself — it only locates the seam problems around
 * delegation (unknown type, missing/mismatched subsection,
 * {@code TrackerSeamValidator}) and hands a present, name-matching subsection
 * to the registered validator as-is.
 *
 * <p>Tracker adapter types are open-ended, not a fixed compile-time enum
 * (unlike {@code verify} check types), so this is a runtime-registered
 * functional hook rather than a Jackson discriminator sealed family: a new
 * adapter registers its validator without the core loader changing.
 *
 * <p>Implements FR17 of add-tracker-port.
 */
@FunctionalInterface
public interface TrackerSubsectionValidator {

    /**
     * Validates one adapter-owned subsection's content.
     *
     * <p>Implements FR17 of add-tracker-port.
     *
     * @param file the offending file, always {@code "config.yaml"}
     * @param where the located field prefix for this subsection (e.g. {@code
     *     tracker.github}); implementations that check nested keys should
     *     extend it (e.g. {@code tracker.github.api-url})
     * @param subsection the raw, untyped subsection content as parsed by
     *     Jackson (maps/lists/scalars only)
     * @return every located problem found in the subsection; empty when valid
     */
    List<ConfigError> validate(String file, String where, Map<String, Object> subsection);
}
