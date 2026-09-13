package com.github.oinsio.gnomish.dashboard;

/**
 * Human-readable labels for each {@link AlertCondition} variant, for the
 * status card's alert lines (FR9 of redesign-dashboard). Split out of {@link
 * DashboardStatusCardRenderer} to keep both files within the project's
 * file-size guidance, mirroring how {@link DashboardHtmlFormatter} is split
 * from {@link DashboardHtmlRenderer}. The exhaustive switch has no {@code
 * default} arm, so a new {@link AlertCondition} variant fails to compile
 * here instead of silently rendering unlabeled.
 *
 * <p>The three sandbox-hygiene labels (add-serve-sandbox-lifecycle) read as
 * symptoms, not as cleanup statistics: UX2 asks for "an instance died or
 * hung", naming the box and task, so that a routine manual age-stop — which
 * raises no condition at all — can never be mistaken for it.
 *
 * <p>Implements FR4, UX3 of add-dashboard-page; NFR-O3, UX2 of add-serve-sandbox-lifecycle.
 */
final class DashboardAlertLabels {

    private DashboardAlertLabels() {}

    /**
     * Returns a short, human-readable label naming the condition.
     *
     * @param condition the flagged condition; never null
     * @return a short, human-readable label naming the condition
     */
    static String label(AlertCondition condition) {
        return switch (condition) {
            case AlertCondition.StaleWhileNotStopped ignored -> "dead daemon";
            case AlertCondition.OccupiedSlotsHeartbeatNotRunning ignored -> "occupied slots not heartbeating";
            case AlertCondition.LongIdleBlocked ignored -> "idle-blocked too long";
            case AlertCondition.TrackerFailuresPresent ignored -> "tracker failures";
            case AlertCondition.ReaperDegraded ignored -> "reaper degraded";
            case AlertCondition.SweepTickOverdue ignored -> "sandbox sweep not running";
            case AlertCondition.SweepTicksSkipped skipped ->
                "sandbox cleanup stalled: " + skipped.consecutiveTicks()
                        + " consecutive ticks reached no claim verdict";
            case AlertCondition.StoppedOrphanIncident incident ->
                "an instance died or hung: stopped " + incident.objectName() + " of task " + incident.taskKey() + " ("
                        + incident.reason() + ")";
            case AlertCondition.RemoteGateOpen gate -> remoteGateOpenLabel(gate);
        };
    }

    /**
     * NFR-O3, UX6 of add-base-ref-resolution: names the target, how long the gate has been open,
     * the last error, and the next probe time — escalating from "remote outage, probing" to
     * "blocked on the remote" once {@code sustainedOpen} is true.
     */
    private static String remoteGateOpenLabel(AlertCondition.RemoteGateOpen gate) {
        String verb = gate.sustainedOpen() ? "blocked on the remote" : "remote outage, probing";
        return verb + ": " + gate.target() + " open since " + gate.openSince() + ", last error: " + gate.lastError()
                + ", next probe at " + gate.nextProbeAt();
    }
}
