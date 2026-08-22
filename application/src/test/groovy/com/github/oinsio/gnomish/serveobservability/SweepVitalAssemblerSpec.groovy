package com.github.oinsio.gnomish.serveobservability

import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickLog
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification

/**
 * {@link SweepVitalAssembler}, task 6.1 of add-serve-sandbox-lifecycle (NFR-O1): the tick log's
 * open tally and {@link Duration}s become the snapshot's closed counts and seconds — and stay
 * absent entirely until a tick has actually completed.
 */
class SweepVitalAssemblerSpec extends Specification {

    static final Instant TICK_AT = Instant.parse('2026-08-06T09:00:00Z')
    static final Duration INTERVAL = Duration.ofMinutes(5)

    def clock = Clock.fixed(TICK_AT, ZoneOffset.UTC)
    def tickLog = new SweepTickLog(Duration.ofDays(7), clock, 20)

    private static SweepVerdict verdict(SweepVerdictCategory category, String taskKey = 'task-1', Duration age = null) {
        new SweepVerdict(category, 'obj', 'main-box', 'tracked', taskKey, 'reason', age)
    }

    // NFR-O1: "no tick yet" is its own state, distinct from a tick that counted zero.
    def "no completed tick assembles no sweep entry at all"() {
        expect:
        SweepVitalAssembler.assemble(tickLog, INTERVAL) == null
    }

    // NFR-O1: last tick time, cadence, counts, and the inventory in seconds.
    def "a completed tick assembles the vital field-for-field"() {
        given:
        tickLog.beginTick()
        tickLog.onVerdict(verdict(SweepVerdictCategory.CHECKED_ALIVE))
        tickLog.onVerdict(verdict(SweepVerdictCategory.KEPT_UNDER_THRESHOLD, 'task-a', Duration.ofDays(2)))
        tickLog.endTick()

        when:
        def vital = SweepVitalAssembler.assemble(tickLog, INTERVAL)

        then:
        vital.lastTickAt() == TICK_AT
        vital.intervalSeconds() == 300L
        vital.counts() == new SweepCounts(1, 1, 0, 0, 0, 0)
        vital.kept() == [
            new KeptEnvironmentEntry('task-a', 172800L, 432000L)
        ]
        vital.keptTotal() == 1
        vital.consecutiveSkippedTicks() == 0
    }

    // NFR-O3: the consecutive-skip run reaches the snapshot, since no single tick's counts show it.
    def "the consecutive skipped-tick run is carried through"() {
        given:
        2.times {
            tickLog.beginTick()
            tickLog.onVerdict(verdict(SweepVerdictCategory.SKIPPED_NO_VERDICT))
            tickLog.endTick()
        }

        expect:
        SweepVitalAssembler.assemble(tickLog, INTERVAL).consecutiveSkippedTicks() == 2
    }

    // NFR-O1: the cadence is the reader's staleness yardstick, so it comes from the caller's own
    //     configured interval rather than being assumed.
    def "the cadence is carried from the caller's interval"() {
        given:
        tickLog.beginTick()
        tickLog.endTick()

        expect:
        SweepVitalAssembler.assemble(tickLog, Duration.ofHours(1)).intervalSeconds() == 3600L
    }
}
