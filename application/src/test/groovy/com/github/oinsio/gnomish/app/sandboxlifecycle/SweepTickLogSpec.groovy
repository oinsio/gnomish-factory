package com.github.oinsio.gnomish.app.sandboxlifecycle

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification

/**
 * {@link SweepTickLog}, task 6.1 of add-serve-sandbox-lifecycle (NFR-O1): the per-tick tally, the
 * kept-environment inventory with its reap margin and bound, and the consecutive-skipped-tick
 * counter the "cleanup silently stalled" alert reads.
 */
class SweepTickLogSpec extends Specification {

    static final Instant TICK_AT = Instant.parse('2026-08-06T09:00:00Z')
    static final Duration REAP_AGE = Duration.ofDays(7)

    def clock = Clock.fixed(TICK_AT, ZoneOffset.UTC)
    def log = new SweepTickLog(REAP_AGE, clock, 20)

    private static SweepVerdict verdict(SweepVerdictCategory category, String taskKey = 'task-1', Duration age = null) {
        new SweepVerdict(category, 'obj', 'main-box', 'tracked', taskKey, 'reason', age)
    }

    // NFR-O1: no tick has completed, so the snapshot honestly says "no sweep data yet".
    def "lastTick is absent until a tick completes"() {
        expect:
        log.lastTick() == null
        log.consecutiveSkippedTicks() == 0
    }

    // NFR-O1: the published counts describe exactly one pass.
    def "endTick publishes this tick's per-category counts and its completion instant"() {
        given:
        log.beginTick()
        log.onVerdict(verdict(SweepVerdictCategory.CHECKED_ALIVE))
        log.onVerdict(verdict(SweepVerdictCategory.CHECKED_ALIVE))
        log.onVerdict(verdict(SweepVerdictCategory.STOPPED_ORPHAN))

        when:
        def record = log.endTick()

        then:
        record.tickAt() == TICK_AT
        record.counts() == [
            (SweepVerdictCategory.CHECKED_ALIVE): 2,
            (SweepVerdictCategory.STOPPED_ORPHAN): 1
        ]
        log.lastTick() == record
    }

    // NFR-O1: counts are per-tick, NOT cumulative — the second tick's record must not carry the
    //     first tick's verdicts, or a quiet pass would read as a busy one.
    def "beginTick discards the previous tick's tally"() {
        given:
        log.beginTick()
        log.onVerdict(verdict(SweepVerdictCategory.DISPOSED_AGED))
        log.endTick()

        when:
        log.beginTick()
        log.onVerdict(verdict(SweepVerdictCategory.CHECKED_ALIVE))
        def second = log.endTick()

        then:
        second.counts() == [(SweepVerdictCategory.CHECKED_ALIVE): 1]
    }

    // NFR-O1: the inventory is per-tick too — an environment reaped or resumed between ticks must
    //     vanish from the next tick's list, or the dashboard would keep offering a dead row.
    def "beginTick discards the previous tick's kept inventory"() {
        given:
        log.beginTick()
        log.onVerdict(verdict(SweepVerdictCategory.KEPT_UNDER_THRESHOLD, 'task-gone', Duration.ofDays(3)))
        log.endTick()

        when:
        log.beginTick()
        log.onVerdict(verdict(SweepVerdictCategory.KEPT_UNDER_THRESHOLD, 'task-still-here', Duration.ofDays(1)))
        def second = log.endTick()

        then:
        second.kept()*.taskKey() == ['task-still-here']
        second.keptTotal() == 1
    }

    // NFR-O1: the inventory answers "what waits for resume, and for how much longer" — ordered
    //     oldest first, as SweepTickRecord's own contract states, so the row closest to reaping
    //     is the first one read regardless of the order the listing named the objects in.
    def "kept verdicts become inventory entries with the reap margin, oldest first"() {
        given:
        log.beginTick()
        log.onVerdict(verdict(SweepVerdictCategory.KEPT_UNDER_THRESHOLD, 'task-a', Duration.ofDays(2)))
        log.onVerdict(verdict(SweepVerdictCategory.KEPT_UNDER_THRESHOLD, 'task-b', Duration.ofDays(6)))

        when:
        def record = log.endTick()

        then:
        record.kept() == [
            new KeptEnvironment('task-b', Duration.ofDays(6), Duration.ofDays(1)),
            new KeptEnvironment('task-a', Duration.ofDays(2), Duration.ofDays(5))
        ]
        record.keptTotal() == 2
    }

    // NFR-O1: the bound must keep the environments an operator can still ACT on — the oldest,
    //     nearest the reap age — never whichever the docker listing happened to name first.
    def "the bound keeps the oldest kept environments, not the first-seen ones"() {
        given:
        def bounded = new SweepTickLog(REAP_AGE, clock, 2)
        bounded.beginTick()
        bounded.onVerdict(verdict(SweepVerdictCategory.KEPT_UNDER_THRESHOLD, 'task-young', Duration.ofDays(1)))
        bounded.onVerdict(verdict(SweepVerdictCategory.KEPT_UNDER_THRESHOLD, 'task-middle', Duration.ofDays(3)))
        bounded.onVerdict(verdict(SweepVerdictCategory.KEPT_UNDER_THRESHOLD, 'task-oldest', Duration.ofDays(6)))

        when:
        def record = bounded.endTick()

        then:
        record.kept()*.taskKey() == ['task-oldest', 'task-middle']
        record.keptTotal() == 3
    }

    // NFR-O1: one kept environment is several objects (box, volume, network) — the operator
    //     decides about the environment, so the rows are deduped on the OLDEST observed age.
    def "several kept objects of one task collapse to one inventory row carrying the oldest age"() {
        given:
        log.beginTick()
        log.onVerdict(verdict(SweepVerdictCategory.KEPT_UNDER_THRESHOLD, 'task-a', Duration.ofDays(2)))
        log.onVerdict(verdict(SweepVerdictCategory.KEPT_UNDER_THRESHOLD, 'task-a', Duration.ofDays(5)))
        log.onVerdict(verdict(SweepVerdictCategory.KEPT_UNDER_THRESHOLD, 'task-a', Duration.ofDays(1)))

        when:
        def record = log.endTick()

        then:
        record.kept() == [
            new KeptEnvironment('task-a', Duration.ofDays(5), Duration.ofDays(2))
        ]
        record.keptTotal() == 1
    }

    // NFR-O1: only kept verdicts feed the inventory; every other category is counted only.
    def "a non-kept verdict never becomes an inventory entry"() {
        given:
        log.beginTick()
        log.onVerdict(verdict(SweepVerdictCategory.DISPOSED_AGED, 'task-a', Duration.ofDays(9)))

        when:
        def record = log.endTick()

        then:
        record.kept().isEmpty()
        record.keptTotal() == 0
        record.counts() == [(SweepVerdictCategory.DISPOSED_AGED): 1]
    }

    // NFR-O1: a kept verdict with no measured age has no margin to report, so it is counted but
    //     not listed — a row with a blank age would be worse than no row.
    def "a kept verdict without a measured age is counted but not inventoried"() {
        given:
        log.beginTick()
        log.onVerdict(verdict(SweepVerdictCategory.KEPT_UNDER_THRESHOLD, 'task-a', null))

        when:
        def record = log.endTick()

        then:
        record.counts() == [(SweepVerdictCategory.KEPT_UNDER_THRESHOLD): 1]
        record.kept().isEmpty()
        record.keptTotal() == 0
    }

    // NFR-O1: the inventory is bounded and its truncation is stated by keptTotal.
    def "the inventory stops at the bound while keptTotal states the truth"() {
        given:
        def bounded = new SweepTickLog(REAP_AGE, clock, 2)
        bounded.beginTick()
        (1..5).each { i ->
            bounded.onVerdict(verdict(SweepVerdictCategory.KEPT_UNDER_THRESHOLD, "task-${i}", Duration.ofDays(1)))
        }

        when:
        def record = bounded.endTick()

        then:
        record.kept()*.taskKey() == ['task-1', 'task-2']
        record.keptTotal() == 5
    }

    // NFR-O1: a margin can only go negative if the sink and the policy disagree about the
    //     threshold; render it as "due now" rather than fail the snapshot write over it.
    def "a kept age past the reap threshold clamps the margin at zero"() {
        given:
        log.beginTick()
        log.onVerdict(verdict(SweepVerdictCategory.KEPT_UNDER_THRESHOLD, 'task-a', Duration.ofDays(9)))

        when:
        def record = log.endTick()

        then:
        record.kept() == [
            new KeptEnvironment('task-a', Duration.ofDays(9), Duration.ZERO)
        ]
    }

    // NFR-O3: consecutive skipped ticks are the "cleanup silently stalled" signal.
    def "consecutive skipped ticks accumulate and reset on the first tick that reaches verdicts"() {
        when: 'two ticks in a row cannot reach a verdict'
        2.times {
            log.beginTick()
            log.onVerdict(verdict(SweepVerdictCategory.SKIPPED_NO_VERDICT))
            log.endTick()
        }

        then:
        log.consecutiveSkippedTicks() == 2
        log.lastTick().consecutiveSkippedTicks() == 2

        when: 'the next tick reaches verdicts for everything'
        log.beginTick()
        log.onVerdict(verdict(SweepVerdictCategory.CHECKED_ALIVE))
        def record = log.endTick()

        then:
        log.consecutiveSkippedTicks() == 0
        record.consecutiveSkippedTicks() == 0
    }

    // NFR-O3: a tick that evaluated nothing at all is not a skipped tick — an empty host is
    //     healthy, and counting it as a stall would cry wolf on every idle daemon.
    def "a tick with no verdicts at all does not count as skipped"() {
        when:
        log.beginTick()
        def record = log.endTick()

        then:
        record.counts().isEmpty()
        record.consecutiveSkippedTicks() == 0
        log.consecutiveSkippedTicks() == 0
    }
}
