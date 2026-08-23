package com.github.oinsio.gnomish.dashboard;

/**
 * Integer share of a total, for the outcome-mix bar segments (FR6) and the
 * token cache-share caption (FR7). The page shows percentages as whole
 * numbers only — a bar segment two pixels wider than its neighbour carries
 * no information a decimal place would add.
 *
 * <p>The one case that must not become a division: a total of zero, which
 * every empty day and every model without cache traffic reaches. It yields
 * 0, so the caller renders an empty bar or the "cache not in use" caption
 * rather than dividing.
 *
 * <p>A standalone helper rather than arithmetic inlined into string
 * assembly (design D5): the zero total and the rounding direction are the
 * two things that can go wrong here, and both need to be reachable to be
 * tested.
 *
 * <p>A stacked bar needs more than the shares rounded one at a time:
 * {@link #shares(long...)} apportions a whole bar, because a mix bar makes
 * two promises independent rounding breaks. Its segments must fill the bar
 * exactly — four shares rounded apart leave a gap the reader cannot account
 * for — and a nonzero outcome must stay visible, where plain rounding drops
 * one aborted task in three hundred to a segment of no width at all, in the
 * one block whose job is to show the rare outcome (FR6).
 *
 * <p>Implements FR6, FR7, M2 of redesign-dashboard (design D5).
 */
final class DashboardPercentage {

    private DashboardPercentage() {}

    /**
     * Returns {@code part} as a whole-number percentage of {@code total},
     * rounded to nearest.
     *
     * @param part the share being measured; a value outside {@code [0, total]} — a corrupt
     *     ledger line, never a composed view — is clamped rather than becoming invalid CSS
     * @param total the whole it is measured against; zero or negative yields 0
     * @return the percentage, clamped to {@code [0, 100]}
     */
    static int of(long part, long total) {
        if (total <= 0L) {
            return 0;
        }
        return (int) Math.clamp(Math.round(exact(part, total)), 0L, 100L);
    }

    /**
     * Apportions one stacked bar across its segments: whole-number shares
     * that sum to exactly 100, where every positive value gets at least 1
     * and every zero value gets 0.
     *
     * <p>Largest remainder: each segment starts at its floored share, and
     * the points still unspent go one at a time to whichever segment sits
     * furthest below its exact share. The minimum width of 1 is paid for the
     * same way in reverse — the points it borrows come off the segments
     * standing furthest above theirs, never off another minimum — so a rare
     * outcome becomes a thin sliver instead of disappearing.
     *
     * @param values each segment's count; a total of zero or less yields all zeros
     * @return one share per value, in the same order; never null
     */
    static int[] shares(long... values) {
        int[] shares = new int[values.length];
        long total = 0L;
        for (long value : values) {
            total += value;
        }
        if (total <= 0L) {
            return shares;
        }
        int assigned = 0;
        for (int i = 0; i < values.length; i++) {
            shares[i] = values[i] <= 0L ? 0 : (int) Math.max(1.0, Math.floor(exact(values[i], total)));
            assigned += shares[i];
        }
        while (assigned < 100) {
            shares[understated(values, shares, total)]++;
            assigned++;
        }
        while (assigned > 100) {
            shares[overstated(values, shares, total)]--;
            assigned--;
        }
        return shares;
    }

    /** The segment sitting furthest below its exact share; ties go to the earliest. */
    private static int understated(long[] values, int[] shares, long total) {
        int furthest = 0;
        double widestGap = -Double.MAX_VALUE;
        for (int i = 0; i < shares.length; i++) {
            double gap = exact(values[i], total) - shares[i];
            if (gap > widestGap) {
                furthest = i;
                widestGap = gap;
            }
        }
        return furthest;
    }

    /**
     * The segment sitting furthest above its exact share, skipping any already at the
     * minimum width of 1 — reclaiming from those would undo the guarantee.
     */
    private static int overstated(long[] values, int[] shares, long total) {
        int furthest = 0;
        double widestGap = -Double.MAX_VALUE;
        for (int i = 0; i < shares.length; i++) {
            double gap = shares[i] - exact(values[i], total);
            if (shares[i] > 1 && gap > widestGap) {
                furthest = i;
                widestGap = gap;
            }
        }
        return furthest;
    }

    private static double exact(long part, long total) {
        return (double) part * 100.0 / (double) total;
    }
}
