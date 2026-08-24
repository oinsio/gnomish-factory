package com.github.oinsio.gnomish.dashboard

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Verifies the integer percentage arithmetic behind the outcome-mix bars
 * and the token cache-share caption (task 2.2 of redesign-dashboard): a
 * zero total never divides, a single-outcome day comes out at a full 100%,
 * and the rounding is nearest rather than truncating so a two-thirds share
 * reads 67%, not 66%.
 *
 * FR6, FR7, M2 of redesign-dashboard (design D5).
 */
class DashboardPercentageSpec extends Specification {

    @Unroll
    def "#part of #total is #expected%"() {
        expect:
        DashboardPercentage.of(part, total) == expected

        where:
        part | total | expected
        // a zero total never divides — the bar has nothing to apportion
        0L | 0L | 0
        5L | 0L | 0
        0L | 7L | 0
        // a single-outcome day fills its bar completely
        7L | 7L | 100
        1L | 1L | 100
        // nearest, not truncating: 2/3 is 67%, and 1/3 is 33%
        2L | 3L | 67
        1L | 3L | 33
        1L | 2L | 50
        1L | 8L | 13
        3L | 8L | 38
        1L | 1000L | 0
        // large values do not overflow on the way to a percentage
        4_000_000_000L | 8_000_000_000L | 50
        9_000_000_000_000_000_000L | 9_000_000_000_000_000_000L | 100
    }

    def "a negative total is treated as nothing to apportion rather than inverting the bar"() {
        expect:
        DashboardPercentage.of(5L, -10L) == 0
    }

    // A part outside [0, total] can only come from a corrupt ledger line; it is
    // clamped rather than becoming width:-40% or width:250% in the bar's style.
    @Unroll
    def "a part outside the total's range is clamped: #part of #total is #expected%"() {
        expect:
        DashboardPercentage.of(part, total) == expected

        where:
        part | total | expected
        -40L | 100L | 0
        250L | 100L | 100
    }

    // FR6: the bar is a mix, so its segments must add up to the whole bar —
    // four independently rounded shares leave a gap the reader cannot account for.
    @Unroll
    def "the segments of #values fill the bar exactly: #expected"() {
        when:
        def shares = DashboardPercentage.shares(values as long[])

        then:
        shares as List == expected
        (shares as List).sum() == 100

        where:
        values | expected
        [1L, 1L, 1L, 0L] | [34, 33, 33, 0]
        [1L, 1L, 1L, 1L] | [25, 25, 25, 25]
        [2L, 1L, 0L, 0L] | [67, 33, 0, 0]
        [1L, 2L, 0L, 0L] | [33, 67, 0, 0]
        [7L, 0L, 0L, 0L] | [100, 0, 0, 0]
        [1L, 1L, 1L, 3L] | [17, 17, 16, 50]
        [25L, 15L, 10L, 50L] | [25, 15, 10, 50]
    }

    // FR6: a rare outcome is exactly what the operator must not miss — one
    // aborted task in 300 rounds to nothing and would vanish from the bar.
    @Unroll
    def "a nonzero share never disappears: #values keeps every outcome visible"() {
        when:
        def shares = DashboardPercentage.shares(values as long[])

        then:
        (shares as List).sum() == 100
        [shares as List, values].transpose().every { share, value ->
            value> 0L ? share >= 1 : share == 0
        }
        shares as List == expected

        where:
        values | expected
        [299L, 1L, 0L, 0L] | [99, 1, 0, 0]
        [297L, 1L, 1L, 1L] | [97, 1, 1, 1]
        [1L, 999_999L, 0L, 0L] | [1, 99, 0, 0]
        [1L, 1L, 999_998L, 0L] | [1, 1, 98, 0]
    }

    // FR6: the minimum width is paid for by whichever segment stands furthest
    //      above its exact share, so the sliver costs the bar the least accuracy.
    @Unroll
    def "the minimum width is paid for by the furthest overstated segment: #values"() {
        when:
        def shares = DashboardPercentage.shares(values as long[])

        then:
        shares as List == expected
        (shares as List).sum() == 100

        where:
        // 300/905 is 33.1% and 600/905 is 66.3% — the point comes off the
        // segment rounded furthest up, not off the widest one
        values | expected
        [3L, 300L, 2L, 600L] | [1, 32, 1, 66]
        [2L, 2L, 606L, 390L] | [1, 1, 60, 38]
        // both large segments stand exactly 0.2 points above their share —
        // an even contest is settled by order, never by dropping two points
        [30L, 30L, 4020L, 5920L] | [1, 1, 39, 59]
    }

    @Unroll
    def "nothing to apportion leaves every segment empty: #values"() {
        expect:
        DashboardPercentage.shares(values as long[]) as List == [0, 0, 0, 0]

        where:
        values << [
            [0L, 0L, 0L, 0L],
            [0L, -5L, 0L, 0L]
        ]
    }

    def "shares scale to large counts without overflowing"() {
        expect:
        DashboardPercentage.shares(4_000_000_000_000L, 4_000_000_000_000L) as List == [50, 50]
    }
}
