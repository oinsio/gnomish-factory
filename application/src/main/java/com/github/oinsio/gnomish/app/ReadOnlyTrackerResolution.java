package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;

/**
 * The pair {@link TrackerWiring#resolveReadOnly} resolves for the standalone read-only commands:
 * the {@code tracker} section and the reader built from it.
 *
 * <p>Implements FR10 of add-project-registry.
 */
record ReadOnlyTrackerResolution(TrackerConfig trackerConfig, Tracker tracker) {}
