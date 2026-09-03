package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.logtext.RepeatSuppressor
import java.time.Duration
import spock.lang.Specification

/**
 * FR4 of harden-logging-observability: the roll-up period is derived from the beat interval, not
 * taken from the suppressor's catalog default. The two defaults are both five minutes, and a
 * roll-up period equal to the loop's own tick suppresses nothing — every repeat would already have
 * outlived the quiet period and would qualify as a roll-up, so a sustained outage would still cost
 * one WARN per beat. The period is therefore several beats long, and never shorter than the
 * catalog default for the very fast intervals a test or a tuned installation may use.
 */
class HeartbeatRollUpPeriodSpec extends Specification {

    def "FR4: the roll-up period outlives the beat interval it watches"() {
        expect:
        InstanceHeartbeat.rollUpFor(interval) == expected

        where:
        interval || expected
        Duration.ofMinutes(5) || Duration.ofMinutes(30)
        Duration.ofSeconds(30) || RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL
        Duration.ofSeconds(50) || RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL
        Duration.ofSeconds(51) || Duration.ofSeconds(306)
        Duration.ofHours(1) || Duration.ofHours(6)
    }

    // The boundary itself: six beats exactly equal to the catalog default is not longer than it,
    // so the default stands — one step above it, the derived period takes over.
    def "FR4: at exactly six beats' worth of default, the default still wins"() {
        given:
        def sixBeatsIsExactlyDefault = RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL.dividedBy(6)

        expect:
        InstanceHeartbeat.rollUpFor(sixBeatsIsExactlyDefault) == RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL

        and:
        InstanceHeartbeat.rollUpFor(sixBeatsIsExactlyDefault.plusSeconds(1)) >
                RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL
    }
}
