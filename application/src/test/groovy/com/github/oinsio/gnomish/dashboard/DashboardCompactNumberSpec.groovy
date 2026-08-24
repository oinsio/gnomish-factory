package com.github.oinsio.gnomish.dashboard

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Verifies the page's compact count rendering (task 2.1 of
 * redesign-dashboard): counts below 1000 print as-is, larger counts scale
 * to K/M/B/T/P/E at three significant digits with trailing zeros dropped,
 * and a rounding that would overflow its unit promotes to the next one
 * instead of printing {@code 1000K}.
 *
 * <p>Three significant digits, not "one decimal place": it is the only rule
 * that produces every value the capability names — {@code 25.6K}, {@code
 * 4.79M}, and {@code 5M} rather than {@code 5.0M}.
 *
 * FR9, M2 of redesign-dashboard (design D5).
 */
class DashboardCompactNumberSpec extends Specification {

    @Unroll
    def "#value renders as #expected"() {
        expect:
        DashboardCompactNumber.format(value) == expected

        where:
        value | expected
        0L | '0'
        1L | '1'
        999L | '999'
        // the exact boundary: the first value that scales at all, with its .00 dropped
        1000L | '1K'
        1049L | '1.05K'
        1234L | '1.23K'
        25_600L | '25.6K'
        25_700L | '25.7K'
        999_499L | '999K'
        // rounding that would overflow the unit promotes instead of printing 1000K
        999_500L | '1M'
        1_000_000L | '1M'
        4_790_000L | '4.79M'
        // the dropped .0: exactly 5M is 5M, never 5.0M
        5_000_000L | '5M'
        1_000_000_000L | '1B'
        1_230_000_000L | '1.23B'
        1_000_000_000_000L | '1T'
        1_000_000_000_000_000L | '1P'
        1_000_000_000_000_000_000L | '1E'
    }

    def "the largest and smallest long stay inside the unit table"() {
        expect:
        DashboardCompactNumber.format(Long.MAX_VALUE) == '9.22E'
        DashboardCompactNumber.format(Long.MIN_VALUE) == '-9.22E'
    }

    @Unroll
    def "negatives keep their sign through the same scaling: #value"() {
        expect:
        DashboardCompactNumber.format(value) == expected

        where:
        value | expected
        -42L | '-42'
        -999L | '-999'
        -1000L | '-1K'
        -25_600L | '-25.6K'
    }
}
