package com.github.oinsio.gnomish.app.serve;

import org.slf4j.LoggerFactory;

/**
 * A push notification that a snapshot-worthy transition just happened and the snapshot writer
 * should wake for an immediate write, without waiting for its timer beat (FR1 of
 * add-serve-observability, design D4's "two trigger points, one write point").
 *
 * <p>Defined in this package, not {@code serveobservability.writer}, so the call sites that fire
 * it — {@link SlotLedger#assign} / {@link SlotLedger#release}, {@link FeedViewTracker}'s
 * state-transition point — can take it as a plain constructor dependency without {@code app.serve}
 * importing {@code serveobservability} (module boundary, process-invariants.md: modules expose an
 * explicit public API). {@code serveobservability.writer} supplies {@code SnapshotWriter::markDirty}
 * as the real implementation at wiring time (task 5.x); until then every caller defaults to {@link
 * #NOOP}.
 *
 * <p>Implements FR1 of add-serve-observability.
 */
@FunctionalInterface
public interface DirtyNotifier {

    /** A notifier that does nothing — the default absent an observability writer to wake. */
    DirtyNotifier NOOP = () -> {};

    /** Fires the notification. Safe to call from any thread; implementations must not block. */
    void markDirty();

    /**
     * Invokes {@link #markDirty()} on {@code notifier}, catching and logging any exception it
     * throws instead of letting it propagate into the caller's own state transition. {@link
     * #markDirty()}'s real implementation ({@code SnapshotWriter::markDirty}, wired at task 5.x)
     * is expected to be trivial and non-throwing — a volatile flag set plus a monitor {@code
     * notifyAll} — but {@link DirtyNotifier} is a pluggable seam, so a future or misbehaving
     * implementation must not be able to break {@link SlotLedger#assign}/{@link
     * SlotLedger#release}, {@link FeedViewTracker#transitionTo}, or {@link
     * LifecycleStateTracker}'s transitions: an observability failure must never propagate into
     * slot or feed logic (NFR-R1 of add-serve-observability).
     *
     * @param notifier the notifier to invoke; never null
     * @param callSite a short label identifying the caller, included in the log message on failure
     */
    static void markDirtySafely(DirtyNotifier notifier, String callSite) {
        try {
            notifier.markDirty();
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(DirtyNotifier.class)
                    .warn(
                            "{}: dirty notifier failed; snapshot write may be delayed until the next timer beat",
                            callSite,
                            e);
        }
    }
}
