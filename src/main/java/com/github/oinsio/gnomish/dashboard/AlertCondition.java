package com.github.oinsio.gnomish.dashboard;

/**
 * One of the operator-guide's dead-man's-switch rules 1–5 (design D9 of
 * add-serve-observability), flagged by {@link AlertConditionEvaluator} as
 * currently true of a snapshot. Modeled as a sealed type — one variant per
 * rule, none carrying extra data — so a renderer switches exhaustively over
 * exactly these five cases (task 3.4) with no {@code default} arm to
 * silently swallow a new one, mirroring {@link DaemonSnapshotView}'s style.
 * Rule 6 (heldClaims vs slot-count desync across two checks) has no variant
 * here: it needs check-to-check history and stays with the external monitor
 * (design D3).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR4 of add-dashboard-page (design D3).
 */
public sealed interface AlertCondition
        permits AlertCondition.StaleWhileNotStopped,
                AlertCondition.OccupiedSlotsHeartbeatNotRunning,
                AlertCondition.LongIdleBlocked,
                AlertCondition.TrackerFailuresPresent,
                AlertCondition.ReaperDegraded {

    /**
     * Rule 1: the snapshot is stale and its last reported lifecycle is not
     * {@code stopped} — the same condition {@link DaemonSnapshotView.DeadDaemon}
     * already classifies.
     */
    record StaleWhileNotStopped() implements AlertCondition {}

    /** Rule 2: {@code slots.entries} is non-empty but the heartbeat is not {@code running}. */
    record OccupiedSlotsHeartbeatNotRunning() implements AlertCondition {}

    /** Rule 3: {@code feed.state == idleBlocked} for longer than the escalation threshold. */
    record LongIdleBlocked() implements AlertCondition {}

    /** Rule 4: {@code tracker.consecutiveFailures} is non-zero. */
    record TrackerFailuresPresent() implements AlertCondition {}

    /**
     * Rule 5: the reaper's {@code lastRunAt} is stale against its own
     * {@code intervalSeconds}, or its {@code restartCount} is positive
     * (crash-looping evidence visible even from a single snapshot).
     */
    record ReaperDegraded() implements AlertCondition {}
}
