package com.github.oinsio.gnomish.app.serve;

import java.time.Instant;

/**
 * {@link FeedAutomaton}'s own read model of its current cycle state (design D3): the observed
 * {@link FeedState}, when it entered that state, the instant of its last tracker poll, the
 * open-front count that poll observed, and the configured WIP limit. Read by {@link
 * FeedAutomaton#view()}; the {@code serveobservability} package's assembler (task 2.3) turns this
 * into the snapshot's {@code feed} section (FR5) without this class or {@link FeedAutomaton}
 * knowing that package exists.
 *
 * <p>{@code lastPollAt}/{@code openFronts} carry the most recent poll's values regardless of the
 * reported {@code state}; while {@code state} is {@link FeedState#FULL} the automaton sends no
 * polls at all (design D1), so both fields are deliberately stale in that state — freshness is
 * meaningful only in Filling/Idle (design D3).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR5 of add-serve-observability.
 *
 * @param state the state observed by the automaton's last completed cycle, or the synthetic
 *     {@link FeedState#FULL} vantage point while blocked acquiring a slot; never null
 * @param since the instant the automaton entered {@code state}; never null
 * @param lastPollAt the instant of the automaton's last tracker poll; never null
 * @param openFronts the open-front count observed at {@code lastPollAt}
 * @param wipLimit the configured work-in-progress limit the feed enforces
 */
public record FeedView(FeedState state, Instant since, Instant lastPollAt, int openFronts, int wipLimit) {}
