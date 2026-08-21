package com.github.oinsio.gnomish.app.sandboxlifecycle;

import java.util.List;

/**
 * Delivers every verdict to several sinks in order (FR9, design D6 of
 * add-serve-sandbox-lifecycle). The daemon needs two of them for the same pass — the snapshot's
 * tick log and the ledger's action lines — while the evaluator's seam takes exactly one listener;
 * this is that adapter, kept as its own type rather than a lambda so the delivery order (and its
 * "every sink sees every verdict" contract) is a thing a spec can assert.
 *
 * <p>Implements FR9, NFR-O1, NFR-O2 of add-serve-sandbox-lifecycle.
 */
public record SweepVerdictFanout(List<SweepVerdictListener> sinks) implements SweepVerdictListener {

    /**
     * @param sinks the sinks every verdict is delivered to, in iteration order; copied defensively
     */
    public SweepVerdictFanout {
        sinks = List.copyOf(sinks);
    }

    @Override
    public void onVerdict(SweepVerdict verdict) {
        for (SweepVerdictListener sink : sinks) {
            sink.onVerdict(verdict);
        }
    }
}
