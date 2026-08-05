package com.github.oinsio.gnomish.serveobservability;

import java.time.Instant;

/**
 * The pure mapping from a daemon startup/shutdown event to the ledger's {@code
 * lifecycle} line (design D6, FR12): {@link #started} and {@link #stopped} build
 * a {@link LifecycleLine} carrying {@link LedgerLifecycleEvent.Started} or {@link
 * LedgerLifecycleEvent.Stopped} respectively — no run totals, since the snapshot
 * keeps only the last state and a silent crash-loop on an empty queue is
 * otherwise invisible in history (design D6).
 *
 * <p>Stateless: a pure function with no fields, following the module's assembler
 * convention (e.g. {@link TaskOutcomeLineAssembler}, {@link TrackerHealthAssembler}).
 *
 * <p>Implements FR12 of add-serve-observability.
 */
public final class LifecycleLineAssembler {

    private LifecycleLineAssembler() {}

    /**
     * Assembles a {@code started} lifecycle line.
     *
     * @param instance the writing process's identity; never null
     * @param at when the process started; never null
     * @return the assembled line
     */
    public static LifecycleLine started(InstanceInfo instance, Instant at) {
        return new LifecycleLine(instance, at, new LedgerLifecycleEvent.Started());
    }

    /**
     * Assembles a {@code stopped} lifecycle line carrying {@code reason}.
     *
     * @param instance the writing process's identity; never null
     * @param at when the process stopped; never null
     * @param reason why the daemon stopped (e.g. {@code "sigterm"}, {@code
     *     "drainComplete"}); never blank
     * @return the assembled line
     */
    public static LifecycleLine stopped(InstanceInfo instance, Instant at, String reason) {
        return new LifecycleLine(instance, at, new LedgerLifecycleEvent.Stopped(reason));
    }
}
