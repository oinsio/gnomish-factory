package com.github.oinsio.gnomish.serveobservability.json;

import org.jspecify.annotations.Nullable;

/**
 * The JSON contract's {@code vitals} section (FR7): heartbeat, reaper,
 * janitor, and the sandbox-lifecycle sweep (NFR-O1 of
 * add-serve-sandbox-lifecycle). No entry for the feed or the writer itself
 * (design D3).
 *
 * @param heartbeat the claim-heartbeat worker's health
 * @param reaper the standing reaper thread's health
 * @param janitor the hourly worktree janitor's health
 * @param sweep the last sandbox-lifecycle sweep tick; null until the first tick completes, and
 *     absent from documents written before add-serve-sandbox-lifecycle
 */
public record VitalsDto(
        HeartbeatDto heartbeat,
        ReaperDto reaper,
        JanitorDto janitor,
        @Nullable SweepDto sweep) {}
