package com.github.oinsio.gnomish.serveobservability;

import java.time.Instant;

/**
 * A ledger {@code sweepTick} line: one per completed sandbox-lifecycle tick, carrying that tick's
 * per-category counts (NFR-O2 of add-serve-sandbox-lifecycle). This is the line that makes a
 * silently stalled sweep visible in history: a day of ticks that found nothing actionable produces
 * only these, so their absence — or a run of them counting nothing but skipped-no-verdict — is
 * itself the evidence, exactly as {@link LifecycleLine} makes a crash-loop on an empty queue
 * visible without any {@link TaskOutcomeLine}.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements NFR-O2 of add-serve-sandbox-lifecycle.
 *
 * @param instance the writing process's identity; never null
 * @param at when the tick completed; never null
 * @param counts the tick's per-category verdict counts, including the untouched ones; never null
 */
public record SweepTickLine(InstanceInfo instance, Instant at, SweepCounts counts) implements LedgerLine {}
