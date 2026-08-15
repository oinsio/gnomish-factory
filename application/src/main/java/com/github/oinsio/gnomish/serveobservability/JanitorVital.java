package com.github.oinsio.gnomish.serveobservability;

import java.time.Instant;

/**
 * The snapshot's {@code vitals.janitor} entry (FR7): the hourly {@code
 * WorktreeJanitor}'s last run time.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR7 of add-serve-observability.
 *
 * @param lastRunAt the last time the janitor completed a sweep; never null
 */
public record JanitorVital(Instant lastRunAt) {}
