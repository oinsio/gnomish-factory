package com.github.oinsio.gnomish.serveobservability.json;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The JSON contract's top-level snapshot document (v1, spec.md): {@code
 * version} (always {@code 1}), {@code writtenAt}, {@code intervalSeconds},
 * and sections {@code instance}, {@code lifecycle}, {@code feed}, {@code
 * slots}, {@code vitals}, {@code tracker}, {@code remote} — exactly those and
 * no others (FR3). Every {@code null} field renders as JSON {@code null} —
 * see {@link SnapshotJson}.
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
 * @param remote the remote outage gate section, one entry per remote target (NFR-O3, UX6 of
 *     add-base-ref-resolution); {@code null} only when reading a document written before this
 *     change — a reader treats that as "no gate known", never as an empty-but-known section
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
        TrackerDto tracker,
        @Nullable Map<String, RemoteDto> remote) {}
