package com.github.oinsio.gnomish.app.serve;

/**
 * A {@link RemoteOutageLedgerSink} that starts as a no-op and is rebound, exactly
 * once, to the real {@code RemoteOutageLedgerWriter} once that writer exists (task 7.4 wiring) —
 * mirrors {@link ForwardingDirtyNotifier}'s role for the same construction-order cycle: {@link
 * RemoteOutageGate} is built before {@code ObservabilityAssembly} constructs the ledger appender
 * its writer needs, so every gate is handed this stand-in and {@link #bind} is called once the
 * real writer exists.
 *
 * <p>Implements NFR-O1, NFR-O3 of add-base-ref-resolution.
 */
public final class ForwardingRemoteOutageLedgerSink implements RemoteOutageLedgerSink {

    private volatile RemoteOutageLedgerSink delegate = RemoteOutageLedgerSink.NONE;

    /**
     * Rebinds subsequent {@link #outageClosed} calls to {@code delegate}. Called exactly once, at wiring
     * time, right after the real writer (the {@code RemoteOutageLedgerWriter}) is constructed.
     *
     * @param delegate the real sink to forward to from now on; never null
     */
    public void bind(RemoteOutageLedgerSink delegate) {
        this.delegate = delegate;
    }

    @Override
    public void outageClosed(RemoteOutageClosedOutage outage) {
        delegate.outageClosed(outage);
    }
}
