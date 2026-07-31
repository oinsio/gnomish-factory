package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;

/**
 * A claim that {@link StalenessMemory} has just judged stale: the task's {@link
 * TaskRef} paired with the exact {@link ClaimVersion} that stood unchanged past the
 * TTL. The reaper (task 4.3) hands both back to {@code
 * Tracker.removeStaleClaim(ref, version)} — the version is the guard the adapter
 * re-checks so a concurrent live beat or a racing reaper converges safely (design
 * D5); the reaper never claims the task for itself.
 *
 * <p>Only a {@code Working} task with a live claim marker can ever appear here — an
 * {@code AwaitingHuman} task and a {@code Working} task whose claim marker is absent
 * ({@code null} version) are never eligible (FR2, FR4).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR2, FR4 of add-claim-heartbeat.
 *
 * @param ref the stale task's canonical identity; never null
 * @param version the claim version that went stale — the reaper's removal guard;
 *     never null
 */
public record StaleClaim(TaskRef ref, ClaimVersion version) {}
