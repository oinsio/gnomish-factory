package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.app.port.tracker.TrackerUnavailableException
import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import spock.lang.Specification

/**
 * FR10, D10, NFR-R3 of add-claim-heartbeat: the bounded terminal-write retry. Time is injected as a
 * virtual {@link Sleeper} advancing a virtual {@link Clock}, so the ~10-minute bound and the
 * exponential backoff are exercised deterministically and instantly — no real sleeping. An outage
 * ({@link TrackerUnavailableException}) retries; a give-up past the bound is DEFERRED; a non-outage
 * fault surfaces at once (a bug must never loop for ten minutes).
 */
class TerminalWriteRetrySpec extends Specification {

    static final Duration BOUND = Duration.ofMinutes(10)

    AtomicReference<Instant> now = new AtomicReference<>(Instant.parse('2026-01-01T00:00:00Z'))
    AtomicInteger slept = new AtomicInteger()
    List<Duration> sleptDurations = new CopyOnWriteArrayList<>()

    Clock clock = { -> now.get() } as Clock
    Sleeper sleeper = { Duration d ->
        slept.incrementAndGet()
        sleptDurations.add(d)
        now.set(now.get() + d)
    } as Sleeper

    private TerminalWriteRetry retry() {
        new TerminalWriteRetry(sleeper, clock, BOUND)
    }

    // FR10: a write that lands on the first attempt confirms without sleeping.
    def "confirms immediately when the write lands first try"() {
        given:
        def attempts = new AtomicInteger()

        when:
        def result = retry().confirm({ attempts.incrementAndGet() })

        then:
        result == TerminalWriteRetry.Result.CONFIRMED
        attempts.get() == 1
        slept.get() == 0
    }

    // FR10, NFR-R3: an outage retries with backoff and confirms once the tracker returns — no real sleep.
    def "retries an outage with backoff and confirms once the write finally lands"() {
        given: 'the write throws an outage on the first #failures attempts, then succeeds'
        def attempts = new AtomicInteger()

        when:
        def result = retry().confirm({
            if (attempts.getAndIncrement() < failures) {
                throw new TrackerUnavailableException('tracker unreachable')
            }
        })

        then:
        result == TerminalWriteRetry.Result.CONFIRMED
        attempts.get() == failures + 1
        slept.get() == failures

        where:
        failures << [1, 3, 5]
    }

    // FR10, D10: a tracker that stays down past the bound gives up as DEFERRED, having held the slot
    //     ~10 min. The clock self-advances on each read (independently of the sleeper) so the bound
    //     is reached even when a mutant drops the sleep — the loop terminates and DEFERRED is asserted
    //     rather than the run hanging; the exact backoff schedule is pinned by the sequence spec below.
    def "gives up as DEFERRED once the bound elapses with the tracker still down"() {
        given: 'a clock that ticks forward two minutes each read, so the bound is reached in a bounded number of polls'
        def ticking = new AtomicReference<Instant>(Instant.parse('2026-01-01T00:00:00Z'))
        Clock advancingClock = {
            ->
            def t = ticking.get()
            ticking.set(t + Duration.ofMinutes(2))
            t
        } as Clock
        def attempts = new AtomicInteger()
        def retry = new TerminalWriteRetry(sleeper, advancingClock, BOUND)

        when:
        def result = retry.confirm({
            attempts.incrementAndGet()
            throw new TrackerUnavailableException('still down')
        })

        then: 'the loop gave up as DEFERRED, having retried more than once before the bound elapsed'
        result == TerminalWriteRetry.Result.DEFERRED
        attempts.get() > 1
        slept.get() > 1
    }

    // FR10, NFR-R3: the backoff doubles from 500ms and is capped at 60s. The loop terminates on the
    //     write finally landing (not on the clock), so this schedule is asserted directly and a mutant
    //     that drops the sleep or breaks the doubling/cap is killed by an empty or wrong sequence.
    def "backs off doubling from 500ms and caps each wait at 60s"() {
        given: 'the write reports the tracker unreachable nine times, then lands on the tenth attempt'
        def attempts = new AtomicInteger()

        when:
        def result = retry().confirm({
            if (attempts.getAndIncrement() < 9) {
                throw new TrackerUnavailableException('tracker down')
            }
        })

        then: 'nine waits landed in the exact exponential-then-capped schedule'
        result == TerminalWriteRetry.Result.CONFIRMED
        sleptDurations.toList() == [
            Duration.ofMillis(500),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(4),
            Duration.ofSeconds(8),
            Duration.ofSeconds(16),
            Duration.ofSeconds(32),
            Duration.ofSeconds(60),
            Duration.ofSeconds(60)
        ]
    }

    // FR10: only an outage retries — a non-outage fault (a bug) surfaces at once, never looping for ten minutes.
    def "propagates a non-outage exception immediately without retrying"() {
        given:
        def attempts = new AtomicInteger()

        when:
        retry().confirm({
            attempts.incrementAndGet()
            throw new IllegalStateException('a real bug, not an outage')
        })

        then:
        thrown(IllegalStateException)
        attempts.get() == 1
        slept.get() == 0
    }
}
