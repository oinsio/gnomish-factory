package com.github.oinsio.gnomish.logtext

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * {@link RepeatSuppressor}: a poll loop that fails every tick must cost the operator a first line,
 * a periodic counted roll-up and a recovery line — never one line per tick.
 *
 * <p>FR4, NFR-R2, UX3 of harden-logging-observability.
 */
class RepeatSuppressorSpec extends Specification {

    static final Instant START = Instant.parse('2026-08-31T10:00:00Z')
    static final Duration ROLL_UP = Duration.ofMinutes(5)

    /** Virtual time: the suppressor's whole contract is about elapsed time, so no spec may sleep. */
    MovableClock clock = new MovableClock(START)
    RepeatSuppressor suppressor = new RepeatSuppressor(clock, ROLL_UP)

    def "FR4: the first failure of a subject is the news"() {
        when:
        def occurrence = suppressor.failed('workflow-poll:run-42', 'connection refused')

        then:
        occurrence == new RepeatOccurrence.First('connection refused')
    }

    def "FR4, UX3: repeats inside the quiet period are DEBUG-shaped, counted, and silent"() {
        given:
        suppressor.failed('workflow-poll:run-42', 'connection refused')

        when: 'four more ticks, a minute apart — still inside the five-minute quiet period'
        def occurrences = (1..4).collect {
            clock.advance(Duration.ofMinutes(1))
            suppressor.failed('workflow-poll:run-42', 'connection refused')
        }

        then: 'each is a plain repeat carrying the running count, none is an announcement'
        occurrences == [
            new RepeatOccurrence.Repeat('connection refused', 2),
            new RepeatOccurrence.Repeat('connection refused', 3),
            new RepeatOccurrence.Repeat('connection refused', 4),
            new RepeatOccurrence.Repeat('connection refused', 5),
        ]
    }

    def "FR4, UX3: once the quiet period elapses the streak rolls up with its count and age"() {
        given:
        suppressor.failed('docker', 'daemon unreachable')
        3.times {
            clock.advance(Duration.ofMinutes(1))
            suppressor.failed('docker', 'daemon unreachable')
        }

        when: 'the tick that crosses the roll-up boundary'
        clock.advance(Duration.ofMinutes(2))
        def occurrence = suppressor.failed('docker', 'daemon unreachable')

        then:
        occurrence == new RepeatOccurrence.RollUp('daemon unreachable', 5, Duration.ofMinutes(5))
    }

    def "FR4: the roll-up re-arms the quiet period rather than latching open"() {
        given: 'a streak that has just rolled up'
        suppressor.failed('docker', 'daemon unreachable')
        clock.advance(ROLL_UP)
        suppressor.failed('docker', 'daemon unreachable') instanceof RepeatOccurrence.RollUp

        when: 'the very next tick'
        clock.advance(Duration.ofSeconds(1))
        def next = suppressor.failed('docker', 'daemon unreachable')

        then: 'quiet again — the console does not get a roll-up per tick from here on'
        next instanceof RepeatOccurrence.Repeat

        when: 'another full quiet period passes'
        clock.advance(ROLL_UP)
        def later = suppressor.failed('docker', 'daemon unreachable')

        then: 'the second roll-up is measured from the streak start, not from the first roll-up'
        later == new RepeatOccurrence.RollUp('daemon unreachable', 4, ROLL_UP.multipliedBy(2).plusSeconds(1))
    }

    def "FR4: a changed reason is news even while the subject stays broken"() {
        given:
        suppressor.failed('github', 'HTTP 500')

        when:
        clock.advance(Duration.ofSeconds(30))
        def occurrence = suppressor.failed('github', 'HTTP 401')

        then: 'a different fault restarts the streak instead of hiding inside the old one'
        occurrence == new RepeatOccurrence.First('HTTP 401')

        when: 'and the new reason then repeats'
        clock.advance(Duration.ofSeconds(30))
        def repeat = suppressor.failed('github', 'HTTP 401')

        then: 'the count starts from the new reason, not from the whole subject history'
        repeat == new RepeatOccurrence.Repeat('HTTP 401', 2)
    }

    def "FR4: subjects are independent — one broken target does not silence another"() {
        given:
        suppressor.failed('poll:a', 'timeout')

        when:
        def other = suppressor.failed('poll:b', 'timeout')

        then:
        other == new RepeatOccurrence.First('timeout')
    }

    def "FR4, UX3: recovery reports the outage and how many failures it covered"() {
        given:
        suppressor.failed('docker', 'daemon unreachable')
        clock.advance(Duration.ofMinutes(1))
        suppressor.failed('docker', 'daemon unreachable')

        when:
        clock.advance(Duration.ofMinutes(2))
        def recovery = suppressor.recovered('docker')

        then:
        recovery.get() == new RepeatRecovery('daemon unreachable', 2, Duration.ofMinutes(3))
    }

    def "FR4: the steady state is silent — a success with no streak reports nothing"() {
        expect:
        suppressor.recovered('never-failed').isEmpty()
    }

    def "FR4: recovery clears the streak, so the next failure is news again"() {
        given:
        suppressor.failed('docker', 'daemon unreachable')
        suppressor.recovered('docker')

        when:
        def occurrence = suppressor.failed('docker', 'daemon unreachable')

        then:
        occurrence == new RepeatOccurrence.First('daemon unreachable')
    }

    def "FR4: recovering twice announces the recovery once"() {
        given:
        suppressor.failed('docker', 'daemon unreachable')
        suppressor.recovered('docker')

        expect:
        suppressor.recovered('docker').isEmpty()
    }

    def "NFR-R2: concurrent reporters of one subject get distinct verdicts, exactly one of them first"() {
        given:
        def reporters = 16
        def barrier = new CountDownLatch(1)
        def pool = Executors.newVirtualThreadPerTaskExecutor()

        when: 'every reporter is parked on the barrier before any of them touches the suppressor'
        def futures = (1..reporters).collect {
            pool.submit({
                barrier.await()
                suppressor.failed('shared', 'down')
            } as Callable)
        }
        barrier.countDown()
        def results = futures*.get()
        pool.shutdown()

        then: 'no verdict is lost or duplicated: one First plus a contiguous count sequence'
        pool.awaitTermination(10, TimeUnit.SECONDS)
        results.count { it instanceof RepeatOccurrence.First } == 1
        results.findAll { it instanceof RepeatOccurrence.Repeat }
        .collect { it.count() }
        .sort() == (2..reporters).toList()
    }

    def "a non-positive roll-up interval is a programming error"() {
        when:
        new RepeatSuppressor(clock, interval)

        then:
        thrown(IllegalArgumentException)

        where:
        interval << [
            Duration.ZERO,
            Duration.ofSeconds(-1)
        ]
    }

    def "the production wiring carries the documented quiet period"() {
        expect:
        // real-time-wiring: the production defaults ARE the subject here — the suppressor is only
        //     constructed and its constant read, no time is ever elapsed on it.
        RepeatSuppressor.system() != null
        RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL == Duration.ofMinutes(5)
    }

    /** A {@link Clock} the spec moves by hand — the suppressor's contract is entirely about elapsed time. */
    static class MovableClock extends Clock {

        private Instant now

        MovableClock(Instant start) {
            this.now = start
        }

        void advance(Duration by) {
            now += by
        }

        @Override
        Instant instant() {
            now
        }

        @Override
        ZoneId getZone() {
            ZoneOffset.UTC
        }

        @Override
        Clock withZone(ZoneId zone) {
            this
        }
    }
}
