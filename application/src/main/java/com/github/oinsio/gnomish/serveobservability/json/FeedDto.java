package com.github.oinsio.gnomish.serveobservability.json;

/**
 * The JSON contract's {@code feed} section (FR5): automaton {@code state}
 * (serialized {@code filling | idleEmpty | idleBlocked | full}), {@code
 * since}, {@code lastPollAt}, {@code openFronts}, {@code wipLimit}.
 *
 * @param state one of {@code filling | idleEmpty | idleBlocked | full}
 * @param since ISO-8601 UTC instant the feed entered {@code state}
 * @param lastPollAt ISO-8601 UTC instant of the feed's last tracker poll
 * @param openFronts the number of tasks currently claimed and in flight
 * @param wipLimit the configured work-in-progress limit
 */
public record FeedDto(String state, String since, String lastPollAt, int openFronts, int wipLimit) {}
