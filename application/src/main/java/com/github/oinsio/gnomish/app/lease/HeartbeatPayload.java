package com.github.oinsio.gnomish.app.lease;

import java.time.Instant;

/**
 * Renders the human-readable progress line one beat writes into the claim marker (design
 * D1): {@code stage=<x> attempt=<n> alive-at=<iso>}. The {@code stage} and {@code attempt}
 * come from the latest {@link HeartbeatProgress.Progress} snapshot; {@code alive-at} is the
 * beat instant read from the instance's injected {@code Clock}, so a reader of the issue
 * sees both where the work is and that the holder was alive at that moment.
 *
 * <p>Core renders this finished text and the port takes it verbatim — the tracker adapter
 * wraps it in its structural marker convention (design D12), never core. This is the D1
 * payload only; task 4.4 refines nothing here, and the marker's format version is the
 * adapter's concern.
 *
 * <p>Implements FR1 of add-claim-heartbeat.
 */
public final class HeartbeatPayload {

    private HeartbeatPayload() {}

    /**
     * Renders the one-line progress payload from a progress snapshot and the beat instant.
     *
     * <p>Implements FR1 of add-claim-heartbeat.
     *
     * @param progress the latest {@code (stage, attempt)} snapshot for the task; never null
     * @param aliveAt the beat instant, rendered as an ISO-8601 instant; never null
     * @return the finished {@code stage=… attempt=… alive-at=…} line; never null
     */
    public static String render(HeartbeatProgress.Progress progress, Instant aliveAt) {
        return "stage=" + progress.stage() + " attempt=" + progress.attempt() + " alive-at=" + aliveAt;
    }
}
