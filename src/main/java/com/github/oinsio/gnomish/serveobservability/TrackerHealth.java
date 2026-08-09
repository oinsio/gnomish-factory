package com.github.oinsio.gnomish.serveobservability;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The snapshot's {@code tracker} section (FR8): outage visibility fed by
 * every tracker-port caller (feed, heartbeat, reaper — design D12), so an
 * outage stays visible even while a saturated feed has stopped polling.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR8 of add-serve-observability.
 *
 * @param lastSuccessAt the last time any tracker-port call succeeded, or
 *     {@code null} if none has ever succeeded
 * @param consecutiveFailures the number of tracker-port calls that have
 *     failed in a row since the last success; never negative
 */
public record TrackerHealth(@Nullable Instant lastSuccessAt, int consecutiveFailures) {}
