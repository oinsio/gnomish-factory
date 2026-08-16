package com.github.oinsio.gnomish.serveobservability;

import java.time.Instant;

/**
 * A ledger {@code lifecycle} line: {@code started} or {@code stopped} with a
 * reason, no run totals (FR12). Makes a silent crash-loop on an empty queue
 * visible in history even though it produces no {@link TaskOutcomeLine}s
 * (design D6).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR12 of add-serve-observability.
 *
 * @param instance the writing process's identity; never null
 * @param at when the event occurred; never null
 * @param event {@code started} or {@code stopped(reason)}; never null
 */
public record LifecycleLine(InstanceInfo instance, Instant at, LedgerLifecycleEvent event) implements LedgerLine {}
