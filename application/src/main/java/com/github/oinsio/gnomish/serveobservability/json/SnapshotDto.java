package com.github.oinsio.gnomish.serveobservability.json;

/**
 * The JSON contract's top-level snapshot document (v1, spec.md): {@code
 * version} (always {@code 1}), {@code writtenAt}, {@code intervalSeconds},
 * and sections {@code instance}, {@code lifecycle}, {@code feed}, {@code
 * slots}, {@code vitals}, {@code tracker} — exactly those and no others
 * (FR3). Every {@code null} field renders as JSON {@code null} — see
 * {@link SnapshotJson}.
 *
 * <p>Implements FR2, FR3, FR10 conventions of add-serve-observability.
 *
 * @param version the contract version; always {@code 1}
 * @param writtenAt ISO-8601 UTC instant this snapshot was written
 * @param intervalSeconds the configured snapshot write interval, in seconds
 * @param instance the writing process's identity
 * @param lifecycle the daemon's lifecycle state
 * @param feed the feed automaton's current view
 * @param slots slot capacity and occupancy
 * @param vitals heartbeat/reaper/janitor thread health
 * @param tracker tracker-port outage visibility
 */
public record SnapshotDto(
        int version,
        String writtenAt,
        long intervalSeconds,
        InstanceDto instance,
        LifecycleDto lifecycle,
        FeedDto feed,
        SlotsDto slots,
        VitalsDto vitals,
        TrackerDto tracker) {}
