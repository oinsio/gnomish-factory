package com.github.oinsio.gnomish.serveobservability;

import java.time.Instant;

/**
 * The snapshot's {@code vitals.reaper} entry (FR7): the standing reaper
 * thread's last run time, how many times its supervisor has restarted it
 * after a death — a growing count is reaping degradation made visible as
 * data, not only as ERROR log lines (design D3) — and the reaper's own tick
 * cadence. The cadence is carried because the reaper ticks on the heartbeat
 * interval (default 5 minutes), not the snapshot-write interval (default 30
 * seconds); without it a reader cannot decide {@code lastRunAt} staleness
 * from the file alone (M1).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR7 of add-serve-observability.
 *
 * @param lastRunAt the last time the reaper completed a run; never null
 * @param restartCount how many times the supervisor has respawned the reaper
 * @param intervalSeconds the reaper's tick cadence, in seconds — the yardstick
 *     for {@code lastRunAt} staleness, distinct from the top-level
 *     {@code intervalSeconds} (the snapshot-write cadence)
 */
public record ReaperVital(Instant lastRunAt, int restartCount, long intervalSeconds) {}
