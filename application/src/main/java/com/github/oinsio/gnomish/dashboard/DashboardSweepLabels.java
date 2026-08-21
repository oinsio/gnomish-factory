package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory;

/**
 * Human-readable action words for the verdict categories that reach the recent-actions table
 * (NFR-O3, UX1 of add-serve-sandbox-lifecycle). Split out of {@link
 * DashboardSandboxHygieneSectionRenderer} for the same reason {@link DashboardAlertLabels} is
 * split out of the daemon section: an exhaustive switch with no {@code default} arm, so a new
 * category fails to compile here instead of silently rendering unlabeled.
 *
 * <p>The three non-acting categories are reachable through this switch only if a caller itemizes
 * an untouched object, which {@code SweepActionLine} already refuses to construct; they map to
 * their plain names rather than to an exception, since a label helper must never be the thing that
 * fails a render.
 *
 * <p>Implements NFR-O3, UX1 of add-serve-sandbox-lifecycle.
 */
final class DashboardSweepLabels {

    private DashboardSweepLabels() {}

    /**
     * Returns the action word for a sweep action row's category.
     *
     * @param category the row's verdict category; never null
     * @return a short action word, e.g. {@code "stopped"}
     */
    static String action(SweepVerdictCategory category) {
        return switch (category) {
            case STOPPED_ORPHAN -> "stopped";
            case DISPOSED_AGED -> "disposed (aged)";
            case DISPOSED_RECONSTRUCTIBLE -> "disposed (reconstructible)";
            case CHECKED_ALIVE -> "checked alive";
            case KEPT_UNDER_THRESHOLD -> "kept";
            case SKIPPED_NO_VERDICT -> "skipped";
        };
    }
}
