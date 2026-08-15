package com.github.oinsio.gnomish.serveobservability;

import java.time.Instant;

/**
 * The snapshot's {@code feed} section (FR5): the feed automaton's current
 * {@link FeedPhase}, the time it entered that state, the last successful
 * poll time, open fronts, and the configured WIP limit. Deliberately omits
 * ready-queue depth: the feed does not poll while {@link FeedPhase#FULL}, so
 * a depth field would lie in that state (design D3).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR5 of add-serve-observability.
 *
 * @param state the feed automaton's current phase; never null
 * @param since when the feed entered {@code state}; never null
 * @param lastPollAt the last time the feed polled the tracker; never null,
 *     though it may be stale by design while {@code state} is {@link
 *     FeedPhase#FULL}
 * @param openFronts the number of tasks currently claimed and in flight
 * @param wipLimit the configured work-in-progress limit the feed enforces
 */
public record FeedSnapshot(FeedPhase state, Instant since, Instant lastPollAt, int openFronts, int wipLimit) {}
