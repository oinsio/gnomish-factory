package com.github.oinsio.gnomish.dashboard;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Renders a count the way the page shows it: below 1000 as itself, at or
 * above 1000 scaled to a unit suffix at three significant digits with
 * trailing zeros dropped — {@code 25.6K}, {@code 4.79M}, {@code 5M} (FR9).
 * The exact value never disappears: every call site pairs this with the raw
 * number in the element's {@code title}, so a hover recovers it (NFR-R2).
 *
 * <p>Three significant digits rather than a fixed decimal place: it is the
 * single rule that yields every form the capability names, where "one
 * decimal" would print {@code 4.8M} for a value the spec spells {@code
 * 4.79M}. Rounding is applied before the unit is committed, so a value that
 * rounds up past its unit's ceiling promotes to the next unit instead of
 * printing {@code 1000K}.
 *
 * <p>A standalone helper rather than arithmetic inlined into string
 * assembly (design D5): the boundaries are where this can go wrong, and
 * they are only testable where they are reachable.
 *
 * <p>Implements FR9, M2 of redesign-dashboard (design D5).
 */
final class DashboardCompactNumber {

    /**
     * Unit suffixes at successive powers of 1000, smallest first. The table runs to {@code E}
     * (10^18) deliberately: {@code Long.MAX_VALUE} is 9.22 x 10^18, so scaling can never run past
     * the last entry and the loop below needs no upper bound of its own.
     */
    private static final String[] UNITS = {"K", "M", "B", "T", "P", "E"};

    /** At or above this the count scales to a unit suffix (FR9). */
    private static final BigDecimal SCALE = BigDecimal.valueOf(1000L);

    /** The page shows three significant digits, rounding halves away from zero. */
    private static final MathContext THREE_SIGNIFICANT = new MathContext(3, RoundingMode.HALF_UP);

    private DashboardCompactNumber() {}

    /**
     * Formats {@code value} in the page's compact form.
     *
     * @param value the raw count; may be negative
     * @return the compact form, e.g. {@code "999"}, {@code "25.6K"}, {@code "5M"}
     */
    static String format(long value) {
        if (value > -1000L && value < 1000L) {
            return Long.toString(value);
        }
        // the guard above already returned every |value| < 1000, so negative here means at most
        // -1000 — phrased against that boundary so it stays observable to a test
        boolean negative = value <= -1000L;
        BigDecimal scaled = BigDecimal.valueOf(value).abs().round(THREE_SIGNIFICANT);
        int unit = -1;
        while (scaled.compareTo(SCALE) >= 0) {
            scaled = scaled.movePointLeft(3);
            unit++;
        }
        return (negative ? "-" : "") + scaled.stripTrailingZeros().toPlainString() + UNITS[unit];
    }
}
