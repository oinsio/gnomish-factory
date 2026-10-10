package com.github.oinsio.gnomish.app.daemon

import com.github.oinsio.gnomish.logtext.RepeatSuppressor
import java.time.Duration
import spock.lang.Specification

/**
 * FR2 of supervise-daemon-loops-and-embed-dashboard (design D2), carrying FR4 of
 * harden-logging-observability: a loop's roll-up period is derived from its interval, not taken
 * from the suppressor's catalog default. A roll-up period equal to the loop's own tick suppresses
 * nothing — every repeat would already have outlived the quiet period and would qualify as a
 * roll-up, so a sustained outage would still cost one WARN per tick. The period is therefore six
 * ticks long, and never shorter than the catalog default for the very fast intervals a test or a
 * tuned installation may use.
 */
class RollUpPeriodSpec extends Specification {

    def "FR2: the roll-up period outlives the interval of the loop it watches"() {
        expect:
        RollUpPeriod.forInterval(interval) == expected

        where:
        interval || expected
        Duration.ofMinutes(5) || Duration.ofMinutes(30)
        Duration.ofSeconds(30) || RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL
        Duration.ofSeconds(50) || RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL
        Duration.ofSeconds(51) || Duration.ofSeconds(306)
        Duration.ofHours(1) || Duration.ofHours(6)
    }

    // The boundary itself: six intervals exactly equal to the catalog default is not longer than
    // it, so the default stands — one step above it, the derived period takes over.
    def "FR2: at exactly six intervals' worth of default, the default still wins"() {
        given:
        def sixTicksIsExactlyDefault = RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL.dividedBy(6)

        expect:
        RollUpPeriod.forInterval(sixTicksIsExactlyDefault) == RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL

        and:
        RollUpPeriod.forInterval(sixTicksIsExactlyDefault.plusSeconds(1)) > RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL
    }
}
