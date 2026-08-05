package com.github.oinsio.gnomish.serveobservability;

import java.time.Instant;

/**
 * The serve daemon's snapshot document (v1): a self-describing gauge file
 * answering "alive? what are the slots doing?" (design D1, D3). Self
 * description ({@code version}, {@code writtenAt}, {@code intervalSeconds})
 * lets a reader compute staleness as {@code now − writtenAt > k ×
 * intervalSeconds} without any access to daemon config (FR2). Content is
 * limited to exactly the sections below — per-task detail, queue depth, and
 * history stay with their own canon (task branch, tracker, ledger — FR3).
 *
 * <p>Pure data: this type carries no behavior and is not wired to a writer
 * thread or any live state source (those are later task groups); it is the
 * document shape and JSON contract only.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR2, FR3 of add-serve-observability.
 *
 * @param version the contract version; always {@code 1}
 * @param writtenAt when this snapshot was written; never null
 * @param intervalSeconds the configured snapshot write interval, in seconds;
 *     the maximum gap between writes absent a transition-triggered immediate
 *     write (design D4)
 * @param instance the writing process's identity; never null
 * @param lifecycle the daemon's lifecycle state; never null
 * @param feed the feed automaton's current view; never null
 * @param slots slot capacity and occupancy; never null
 * @param vitals heartbeat/reaper/janitor thread health; never null
 * @param tracker tracker-port outage visibility; never null
 */
public record Snapshot(
        int version,
        Instant writtenAt,
        long intervalSeconds,
        InstanceInfo instance,
        LifecycleState lifecycle,
        FeedSnapshot feed,
        SlotsSnapshot slots,
        VitalsSnapshot vitals,
        TrackerHealth tracker) {

    /**
     * Returns a copy with the self-description fields ({@code writtenAt},
     * {@code intervalSeconds}) replaced and every content section unchanged.
     * The single stamping point that lets {@code SnapshotWriter} guarantee
     * every snapshot actually written to disk carries the real wall-clock
     * time of that write and the configured beat interval, regardless of
     * when the content sections were assembled by the supplier — a reader
     * with only the file can then compute staleness as
     * {@code now − writtenAt > k × intervalSeconds} (design D4).
     *
     * <p>Implements FR2 of add-serve-observability.
     *
     * @param writtenAt the actual wall-clock time of this write; never null
     * @param intervalSeconds the configured snapshot write interval, in
     *     seconds
     * @return a copy of this snapshot with the self-description fields
     *     replaced
     */
    public Snapshot withSelfDescription(Instant writtenAt, long intervalSeconds) {
        return new Snapshot(version, writtenAt, intervalSeconds, instance, lifecycle, feed, slots, vitals, tracker);
    }
}
