package com.github.oinsio.gnomish.serveobservability.json;

import org.jspecify.annotations.Nullable;

/**
 * The JSON contract's {@code tracker} section (FR8): {@code lastSuccessAt}
 * (nullable if the tracker has never succeeded), {@code consecutiveFailures}.
 *
 * @param lastSuccessAt ISO-8601 UTC instant of the last tracker-call
 *     success, or {@code null} if none has ever succeeded
 * @param consecutiveFailures the number of tracker-port calls that have
 *     failed in a row since the last success
 */
public record TrackerDto(@Nullable String lastSuccessAt, int consecutiveFailures) {}
