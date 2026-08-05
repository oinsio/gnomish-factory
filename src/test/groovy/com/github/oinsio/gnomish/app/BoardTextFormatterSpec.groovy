package com.github.oinsio.gnomish.app

import java.time.Instant
import spock.lang.Specification

/**
 * BoardTextFormatter.claimAge / humanDuration: the coarse "updated {age} ago" freshness string a
 * Working row shows (FR4, design D6). This spec exercises every duration branch — seconds,
 * minutes, hours, days — with their exact rollover boundaries, plus zero and a negative age (a
 * claim marker observed "in the future"), which the shorter BoardTextRendererSpec only touches at
 * the minutes branch.
 *
 * <p>Implements FR4 of add-board-command.
 */
class BoardTextFormatterSpec extends Specification {

    private static final Instant NOW = Instant.parse('2026-08-05T12:00:00Z')

    // FR4: humanDuration renders each branch and boundary exactly, and folds a negative age to its
    // magnitude (the claim marker is display-only, never a staleness verdict — design D6).
    def "renders claim age as #expected for an age of #offsetSeconds seconds"() {
        given: 'a claim updated offsetSeconds before the observation instant (negative = in the future)'
        def updatedAt = NOW.minusSeconds(offsetSeconds)

        expect:
        BoardTextFormatter.claimAge(updatedAt, NOW) == expected

        where:
        offsetSeconds || expected
        0             || 'updated 0s ago'
        30            || 'updated 30s ago'
        59            || 'updated 59s ago'
        60            || 'updated 1m ago'
        3540          || 'updated 59m ago'
        3600          || 'updated 1h ago'
        82800         || 'updated 23h ago'
        86400         || 'updated 1d ago'
        172800        || 'updated 2d ago'
        -30           || 'updated 30s ago'
    }
}
