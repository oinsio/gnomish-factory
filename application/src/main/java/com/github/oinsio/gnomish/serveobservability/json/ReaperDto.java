package com.github.oinsio.gnomish.serveobservability.json;

/**
 * The JSON contract's {@code vitals.reaper} entry (FR7): {@code lastRunAt},
 * {@code restartCount}, {@code intervalSeconds}.
 *
 * @param lastRunAt ISO-8601 UTC instant of the reaper's last completed run
 * @param restartCount how many times the supervisor has respawned the reaper
 * @param intervalSeconds the reaper's tick cadence, in seconds — the staleness
 *     yardstick for {@code lastRunAt}, distinct from the top-level
 *     {@code intervalSeconds} (the snapshot-write cadence)
 */
public record ReaperDto(String lastRunAt, int restartCount, long intervalSeconds) {}
