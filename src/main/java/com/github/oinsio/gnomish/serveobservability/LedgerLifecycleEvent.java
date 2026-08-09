package com.github.oinsio.gnomish.serveobservability;

/**
 * A ledger {@code lifecycle} line's event: {@link Started} or {@link
 * Stopped} with a reason (FR12). Modeled as a sealed type rather than an
 * enum plus a nullable reason field, mirroring {@link LifecycleState}'s
 * precedent: the compiler enforces that only {@link Stopped} carries a
 * reason. Unlike the snapshot's {@link LifecycleState}, there is no {@code
 * Draining}/{@code Stopping} counterpart — the ledger records only the two
 * durable endpoints of a process's life, not its in-between states (those
 * live in the snapshot only).
 *
 * <p>{@link Stopped} SHALL NOT carry run totals (FR12) — that is
 * {@link RunSummaryLine}'s job, written separately for drain runs only
 * (FR13).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR12 of add-serve-observability.
 */
public sealed interface LedgerLifecycleEvent permits LedgerLifecycleEvent.Started, LedgerLifecycleEvent.Stopped {

    /** The daemon process started. */
    record Started() implements LedgerLifecycleEvent {}

    /**
     * The daemon process stopped.
     *
     * @param reason why the daemon stopped (e.g. {@code "sigterm"}, {@code
     *     "drainComplete"}); never blank
     */
    record Stopped(String reason) implements LedgerLifecycleEvent {

        public Stopped {
            if (reason.isBlank()) {
                throw new IllegalArgumentException("LedgerLifecycleEvent.Stopped.reason must not be blank");
            }
        }
    }
}
