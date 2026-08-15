package com.github.oinsio.gnomish.app.lease;

/**
 * The seam through which {@link InstanceHeartbeat} signals that its reported {@link
 * InstanceHeartbeat#state()} may just have transitioned — the worker started (→ {@code RUNNING}),
 * died abnormally (→ {@code DIED}), or stopped on an empty held set (→ {@code IDLE}) — so a listener
 * reacts immediately instead of waiting to poll.
 *
 * <p>This is the <b>heartbeat-state</b> trigger of the serve snapshot writer (add-serve-observability
 * FR1, FR7; design D4's "two trigger points, one write point"): the serve wiring adapts {@code
 * SnapshotWriter::markDirty} — via {@code app.serve.DirtyNotifier} — onto this seam, so a heartbeat
 * death lands {@code vitals.heartbeat.state: died} in the snapshot at once rather than up to a full
 * timer interval later. The seam lives here in {@code app.lease}, and the heartbeat takes it rather
 * than {@code app.serve}'s {@code DirtyNotifier} directly, precisely so {@code app.lease} never
 * depends on {@code app.serve} — which already depends on {@code app.lease} — avoiding a package
 * cycle (process-invariants.md, module boundaries). It mirrors {@link ClaimLostSink}: a notify-only
 * seam with an {@link #IGNORE} no-op for the {@code take} run and tests that carry no observability
 * writer to wake.
 *
 * <p>Implements FR1, FR7 of add-serve-observability.
 */
@FunctionalInterface
public interface HeartbeatStateListener {

    /** A listener that discards the signal — the {@code take} run and off-observability tests. */
    HeartbeatStateListener IGNORE = () -> {};

    /**
     * Signals that {@link InstanceHeartbeat#state()} may have transitioned. Invoked once per
     * transition, after it is committed and outside the heartbeat's lock. Implementations MUST be
     * trivial and non-blocking; they SHOULD NOT throw — {@link InstanceHeartbeat} guards the call so
     * an observability failure never breaks beating (NFR-R1 of add-serve-observability), but a
     * throwing listener still costs a logged warning on every transition.
     */
    void onStateChanged();
}
