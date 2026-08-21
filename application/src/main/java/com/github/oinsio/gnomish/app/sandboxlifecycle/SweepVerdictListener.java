package com.github.oinsio.gnomish.app.sandboxlifecycle;

/**
 * The sweep-lifecycle evaluator's event sink seam (`sandbox-lifecycle` "Uniform verdict events",
 * FR9, design D6): {@code sandbox/docker}'s decision-matrix evaluator calls this once per
 * evaluated object; each entry point wires a different implementation — a daemon ledger writer
 * and sweeper vitals for `serve` (task 6.x), a structured SLF4J line for `run`/`take` (task 4.3,
 * 4.4). {@link #IGNORE} is the no-op used until an entry point wires a real sink.
 */
@FunctionalInterface
public interface SweepVerdictListener {

    /** The no-op sink: a caller that does not yet wire a real one is unaffected. */
    SweepVerdictListener IGNORE = _ -> {};

    /**
     * Notifies the sink of one object's verdict.
     *
     * @param verdict the verdict just reached for one object; never null
     */
    void onVerdict(SweepVerdict verdict);
}
