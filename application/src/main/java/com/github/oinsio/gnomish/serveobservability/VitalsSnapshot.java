package com.github.oinsio.gnomish.serveobservability;

import org.jspecify.annotations.Nullable;

/**
 * The snapshot's {@code vitals} section (FR7): heartbeat, reaper, and
 * janitor thread health, plus the sandbox-lifecycle sweep's own entry
 * (NFR-O1 of add-serve-sandbox-lifecycle). Deliberately no entry for the
 * feed (its own {@code feed} section is only readable together with its
 * state — design D3) or the snapshot writer itself (its pulse is the
 * top-level {@code writtenAt}).
 *
 * <p>{@code sweep} is the one nullable section: it stays absent until the
 * daemon's first sweep tick completes, and is absent altogether from
 * snapshots written by builds before add-serve-sandbox-lifecycle. Readers
 * render "no sweep data yet" rather than inventing zeros, so a fresh daemon
 * and a stalled sweep never look alike.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR7 of add-serve-observability; NFR-O1 of add-serve-sandbox-lifecycle.
 *
 * @param heartbeat the claim-heartbeat worker's health; never null
 * @param reaper the standing reaper thread's health; never null
 * @param janitor the hourly worktree janitor's health; never null
 * @param sweep the last sandbox-lifecycle sweep tick, or null if none has completed
 */
public record VitalsSnapshot(
        HeartbeatVital heartbeat,
        ReaperVital reaper,
        JanitorVital janitor,
        @Nullable SweepVital sweep) {

    /**
     * The pre-sweep shape, for callers with no sweep tick to report — a snapshot assembled before
     * the first tick, and every existing caller that predates add-serve-sandbox-lifecycle.
     *
     * @param heartbeat the claim-heartbeat worker's health; never null
     * @param reaper the standing reaper thread's health; never null
     * @param janitor the hourly worktree janitor's health; never null
     */
    public VitalsSnapshot(HeartbeatVital heartbeat, ReaperVital reaper, JanitorVital janitor) {
        this(heartbeat, reaper, janitor, null);
    }
}
