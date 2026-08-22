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
 * <p>add-serve-sandbox-lifecycle adds the three sandbox-hygiene conditions
 * (NFR-O3, UX2), evaluated by {@link SandboxHygieneAlertEvaluator} over the
 * hygiene view rather than by {@link AlertConditionEvaluator} over the
 * snapshot alone — the last of them is a ledger fact, not a snapshot one.
 * They are also the first variants to carry data: UX2 requires the
 * dead-instance alert to NAME the box and its task, so the label cannot be a
 * constant string.
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
                AlertCondition.ReaperDegraded,
                AlertCondition.SweepTickOverdue,
                AlertCondition.SweepTicksSkipped,
                AlertCondition.StoppedOrphanIncident {

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

    /**
     * NFR-O3 of add-serve-sandbox-lifecycle: no sweep tick has completed for
     * longer than a multiple of the sweep's own cadence — cleanup is not
     * running at all, as distinct from running and finding nothing.
     */
    record SweepTickOverdue() implements AlertCondition {}

    /**
     * NFR-O3: consecutive ticks ended without a claim verdict — the sweep is
     * ticking but deciding nothing, so orphans accumulate silently.
     *
     * @param consecutiveTicks how many ticks in a row ended skipped
     */
    record SweepTicksSkipped(int consecutiveTicks) implements AlertCondition {}

    /**
     * UX2: a {@code tracked} running box was stopped because its claim went
     * stale — the symptom of an instance that died or hung, named so the
     * operator can act, and deliberately not raised for a routine {@code
     * manual} age-policy stop.
     *
     * @param objectName the stopped box's own name; never blank
     * @param taskKey the task whose environment it was; never blank
     * @param reason the verdict's short reason; never blank
     */
    record StoppedOrphanIncident(String objectName, String taskKey, String reason) implements AlertCondition {}
}
