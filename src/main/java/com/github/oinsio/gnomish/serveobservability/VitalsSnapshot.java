package com.github.oinsio.gnomish.serveobservability;

/**
 * The snapshot's {@code vitals} section (FR7): heartbeat, reaper, and
 * janitor thread health. Deliberately no entry for the feed (its own {@code
 * feed} section is only readable together with its state — design D3) or
 * the snapshot writer itself (its pulse is the top-level {@code writtenAt}).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR7 of add-serve-observability.
 *
 * @param heartbeat the claim-heartbeat worker's health; never null
 * @param reaper the standing reaper thread's health; never null
 * @param janitor the hourly worktree janitor's health; never null
 */
public record VitalsSnapshot(HeartbeatVital heartbeat, ReaperVital reaper, JanitorVital janitor) {}
