package com.github.oinsio.gnomish.serveobservability;

import com.github.oinsio.gnomish.app.port.tracker.TrackerHealthTracker;

/**
 * Builds the snapshot's {@code tracker} section (FR8) from {@link TrackerHealthTracker}, the
 * port-level decorator shared by feed, heartbeat, and reaper (design D12): carries {@link
 * TrackerHealthTracker#lastSuccessAt()} and {@link TrackerHealthTracker#consecutiveFailures()}
 * across verbatim into {@link TrackerHealth}.
 *
 * <p>Stateless: holds no fields, only assembles a fresh {@link TrackerHealth} from the decorator
 * handed to it on each call.
 *
 * <p>Implements FR8, D12 of add-serve-observability.
 */
public final class TrackerHealthAssembler {

    private TrackerHealthAssembler() {}

    /**
     * Assembles the {@code tracker} section from {@code tracker}'s current counters.
     *
     * @param tracker the shared health decorator whose counters become the snapshot's {@code
     *     tracker} section; never null
     * @return the assembled {@link TrackerHealth}; never null
     */
    public static TrackerHealth assemble(TrackerHealthTracker tracker) {
        return new TrackerHealth(tracker.lastSuccessAt(), tracker.consecutiveFailures());
    }
}
