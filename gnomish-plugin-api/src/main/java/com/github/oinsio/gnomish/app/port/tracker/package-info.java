/**
 * The {@code Tracker} port and its value model (design D15): the {@code
 * Tracker} interface itself, plus {@code TaskRef}, {@code TaskSnapshot}, {@code
 * AbortFacts}, {@code AbortRecord}, {@code TrackerTaskState} and its variants,
 * {@code ParkReason}, {@code ClaimResult}, {@code HumanReply}, {@code
 * ReadyTask}, and {@code TrackerTask}, plus the lease-maintenance value model
 * (add-claim-heartbeat): {@code ClaimVersion}, {@code OpenTask}, {@code
 * HeartbeatResult}, {@code RemoveStaleClaimResult}, and the outage signal
 * {@code TrackerUnavailableException}, plus {@code InstanceId}, the per-process
 * identity attributed to claims and heartbeats.
 *
 * <p>This package speaks the factory's own vocabulary (design D1): tasks,
 * states, decisions, abort facts — never a tracker-specific concept such as a
 * label or an issue. All tracker-specific mapping is confined to adapters under
 * {@code adapter.tracker.*}.
 *
 * <p>Implements FR1, FR2 of add-tracker-port; FR5, FR9, FR10, NFR-R3 of
 * add-claim-heartbeat.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.app.port.tracker;

import org.jspecify.annotations.NullMarked;
