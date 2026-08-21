package com.github.oinsio.gnomish.dashboard

import spock.lang.Specification

/**
 * {@link DashboardDurationFormatter}, task 6.3 of add-serve-sandbox-lifecycle (UX1): the kept
 * inventory's ages and reap margins must answer "how long do I still have" without arithmetic, so
 * every boundary between the four unit ranges — and the "drop a zero minor unit" rule — is pinned.
 */
class DashboardDurationFormatterSpec extends Specification {

    def "formats a span as at most two coarse units"() {
        expect:
        DashboardDurationFormatter.format(seconds) == formatted

        where:
        seconds | formatted
        0L | '0s'
        45L | '45s'
        59L | '59s'
        60L | '1m'
        61L | '1m 1s'
        3599L | '59m 59s'
        3600L | '1h'
        3660L | '1h 1m'
        86399L | '23h 59m'
        86400L | '1d'
        90000L | '1d 1h'
        172800L | '2d'
        432000L | '5d'
        518400L | '6d'
        561600L | '6d 12h'
    }
}
