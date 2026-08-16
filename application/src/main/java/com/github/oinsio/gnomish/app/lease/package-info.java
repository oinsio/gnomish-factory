/**
 * Core lease-maintenance policy over the {@code Tracker} port (add-claim-heartbeat,
 * fix-reaper-idle-liveness): the pure staleness judgment that decides which held claims
 * have gone stale by local observation of claim versions (design D2), plus the
 * monotonic-time seam it measures TTL on, and the instance heartbeat thread (task 4.2)
 * that beats every held claim on the interval with an engine-event-derived progress
 * payload (design D1, D3). Reaping is a standing duty on its own thread,
 * {@code StandingReaper}, that ticks {@code ReaperDuty} for the run's whole lifetime
 * independent of held claims, reading the beat thread's live-claim snapshot only through
 * {@code InstanceHeartbeat.liveClaimsSnapshot()} (fix-reaper-idle-liveness D1, D3). A lost
 * claim (a {@code ClaimGone} beat, task 4.4) is surfaced through the {@code
 * ClaimLostSink} into a thread-safe {@code ClaimLossFlag} the take run polls at each round
 * boundary. It holds only policy — no HTTP, no tracker physics; the two threads here — the
 * instance-level beat thread and the standing reaper thread — reach the tracker only
 * through the port.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.app.lease;

import org.jspecify.annotations.NullMarked;
