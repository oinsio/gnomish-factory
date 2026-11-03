package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.domain.engine.EngineEvent;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;

/**
 * An {@link EngineEventListener} that starts as a no-op and is rebound, exactly once, to the real
 * {@code roundOutcome} ledger writer once that writer exists (NFR-O2, UX3 of
 * define-executor-contract). Mirrors {@link ForwardingRemoteOutageLedgerSink} for the same
 * construction-order cycle: the serve slot wiring, whose engines fan into this listener, is built
 * before {@code ObservabilityAssembly} constructs the ledger appender the writer needs. The field
 * is {@code volatile}, so the slots' engine threads see the rebinding; no slot runs before it.
 *
 * <p>Implements NFR-O2, UX3 of define-executor-contract.
 */
public final class ForwardingRoundLedgerListener implements EngineEventListener {

    private volatile EngineEventListener delegate = event -> {};

    /**
     * Rebinds subsequent {@link #onEvent} calls to {@code delegate}. Called exactly once, at wiring
     * time, right after the real writer is constructed.
     *
     * @param delegate the real listener to forward to from now on; never null
     */
    public void bind(EngineEventListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onEvent(EngineEvent event) {
        delegate.onEvent(event);
    }
}
