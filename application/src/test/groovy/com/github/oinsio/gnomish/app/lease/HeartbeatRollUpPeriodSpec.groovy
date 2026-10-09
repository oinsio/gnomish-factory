package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.daemon.RollUpPeriod
import java.time.Duration
import spock.lang.Specification

/**
 * FR2 of supervise-daemon-loops-and-embed-dashboard (design D2), carrying FR4 of
 * harden-logging-observability: the heartbeat is exempt from the supervised loop but shares its
 * roll-up rule, so its period comes from the rule's one owner, {@link RollUpPeriod} — whose own
 * spec ({@code RollUpPeriodSpec}) holds the table. This spec pins only the delegation.
 */
class HeartbeatRollUpPeriodSpec extends Specification {

    def "FR2: the heartbeat's roll-up period is the loop rule applied to its beat interval"() {
        expect:
        new BeatTiming(interval, interval).rollUp() == RollUpPeriod.forInterval(interval)
        new BeatTiming(interval, interval).rollUp() == expected

        where:
        interval || expected
        Duration.ofMinutes(5) || Duration.ofMinutes(30)
        Duration.ofHours(1) || Duration.ofHours(6)
    }
}
