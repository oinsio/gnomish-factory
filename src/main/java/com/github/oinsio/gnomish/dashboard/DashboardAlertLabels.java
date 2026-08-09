package com.github.oinsio.gnomish.dashboard;

/**
 * Human-readable labels for each {@link AlertCondition} variant, for the
 * daemon section's alert highlight (task 3.4). Split out of {@link
 * DashboardDaemonSectionRenderer} to keep both files within the project's
 * file-size guidance, mirroring how {@link DashboardHtmlFormatter} is split
 * from {@link DashboardHtmlRenderer}. The exhaustive switch has no {@code
 * default} arm, so a new {@link AlertCondition} variant fails to compile
 * here instead of silently rendering unlabeled.
 *
 * <p>Implements FR4, UX3 of add-dashboard-page (design D3).
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
        };
    }
}
