package com.github.oinsio.gnomish.app.serve;

/**
 * The role a {@link RemoteOutageGate} needs from the observability plane: told exactly once per
 * closed outage, so its {@code remoteOutage} ledger line can be appended (task 7.4 of
 * add-base-ref-resolution). A seam a spec fakes is a role interface in the owning module, never a
 * JDK functional type (clause (iii) of design D22 of supervise-daemon-loops-and-embed-dashboard) —
 * this replaces a {@code Consumer<RemoteOutageClosedOutage>}, and with it the {@code andThen}
 * default its forwarding stand-in had to be exempted from. Implemented by the ledger writer and by
 * {@link ForwardingRemoteOutageLedgerSink}, the stand-in the gate is built with.
 *
 * <p>Implements NFR-O1, NFR-O3 of add-base-ref-resolution; FR18 of
 * supervise-daemon-loops-and-embed-dashboard.
 */
@FunctionalInterface
public interface RemoteOutageLedgerSink {

    /** A sink that records nothing — the default absent a ledger to append to. */
    RemoteOutageLedgerSink NONE = ignored -> {};

    /**
     * Records one closed outage.
     *
     * @param outage the closed outage's summary; never null
     */
    void outageClosed(RemoteOutageClosedOutage outage);
}
