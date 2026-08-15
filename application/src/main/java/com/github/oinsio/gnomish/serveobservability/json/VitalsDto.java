package com.github.oinsio.gnomish.serveobservability.json;

/**
 * The JSON contract's {@code vitals} section (FR7): heartbeat, reaper,
 * janitor. No entry for the feed or the writer itself (design D3).
 *
 * @param heartbeat the claim-heartbeat worker's health
 * @param reaper the standing reaper thread's health
 * @param janitor the hourly worktree janitor's health
 */
public record VitalsDto(HeartbeatDto heartbeat, ReaperDto reaper, JanitorDto janitor) {}
