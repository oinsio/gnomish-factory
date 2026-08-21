package com.github.oinsio.gnomish.dashboard;

/**
 * Renders a span of seconds as the coarse, human-scannable form the sandbox hygiene section reads
 * in — {@code 6d 4h}, {@code 3h 20m}, {@code 45s} — rather than as a raw count the operator would
 * have to divide (UX1 of add-serve-sandbox-lifecycle: the ages and reap margins must answer "how
 * long do I still have" at a glance). Package-private and split out of {@link
 * DashboardSandboxHygieneSectionRenderer} for file size, mirroring {@link DashboardHtmlFormatter}'s
 * split from {@link DashboardHtmlRenderer}.
 *
 * <p>Two units at most, largest first, and never a bare {@code 0} unit alongside a larger one: an
 * inventory row is a decision aid, not a stopwatch.
 *
 * <p>Implements UX1 of add-serve-sandbox-lifecycle.
 */
final class DashboardDurationFormatter {

    private static final long MINUTE = 60L;
    private static final long HOUR = 60L * MINUTE;
    private static final long DAY = 24L * HOUR;

    private DashboardDurationFormatter() {}

    /**
     * Formats {@code seconds} as at most two coarse units.
     *
     * @param seconds a non-negative span in seconds
     * @return the formatted span, e.g. {@code "6d 4h"}, {@code "45s"}, {@code "0s"}
     */
    static String format(long seconds) {
        if (seconds >= DAY) {
            return unit(seconds / DAY, "d", seconds % DAY / HOUR, "h");
        }
        if (seconds >= HOUR) {
            return unit(seconds / HOUR, "h", seconds % HOUR / MINUTE, "m");
        }
        if (seconds >= MINUTE) {
            return unit(seconds / MINUTE, "m", seconds % MINUTE, "s");
        }
        return seconds + "s";
    }

    private static String unit(long major, String majorSuffix, long minor, String minorSuffix) {
        if (minor == 0) {
            return major + majorSuffix;
        }
        return major + majorSuffix + " " + minor + minorSuffix;
    }
}
