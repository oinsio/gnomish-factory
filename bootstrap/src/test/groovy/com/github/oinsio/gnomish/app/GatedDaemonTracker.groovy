package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask

/**
 * The daemon's side of a serve isolation spec: an {@link InMemoryTracker} whose first listing —
 * {@code listReady} or {@code listOpen}, whichever the daemon reaches first — waits on a gate the
 * spec supplies (a bounded wait for the page to break), so everything the daemon then claims and
 * delivers provably happens after the page's failure. Every concurrent first reader waits on the
 * one gate; {@link #gateOpened} records whether the gate opened in time, once.
 *
 * <p>Test fixture. Implements FR10, NFR-R1 of supervise-daemon-loops-and-embed-dashboard.
 */
class GatedDaemonTracker extends InMemoryTracker {

    private final Closure<Boolean> gate

    /** The gate's verdict: empty until the daemon's first read, then {@code [true]} if it opened in time. */
    final List<Boolean> gateOpened = []

    /** @param gate waits, bounded, for the page's failure; true if it happened in time */
    GatedDaemonTracker(Closure<Boolean> gate) {
        this.gate = gate
    }

    @Override
    List<ReadyTask> listReady(int limit) {
        passGate()
        super.listReady(limit)
    }

    @Override
    List<OpenTask> listOpen() {
        passGate()
        super.listOpen()
    }

    private void passGate() {
        synchronized (gateOpened) {
            if (gateOpened.isEmpty()) {
                gateOpened << gate.call()
            }
        }
    }
}
