package com.github.oinsio.gnomish.app.lease

import java.time.Duration
import spock.lang.Specification

/**
 * FR4 of fix-reaper-idle-liveness (design D5): {@link RestartBackoff}, the supervised-restart
 * bookkeeping for {@link StandingReaper}. These specs pin the pure state machine directly — the
 * exponential backoff (base, doubling, cap, clean-tick reset) and the lifetime restart counter that
 * never resets — independent of the reaper that drives it in {@code StandingReaperSupervisionSpec}.
 *
 * <p>Note: the {@code >=} cap comparison in {@link RestartBackoff#nextBackoff} carries a documented
 * equivalent-mutant exception (see the method's own comment): weakening it to {@code >} only delays
 * the return by one more monotonic doubling and returns the same {@code MAX_BACKOFF}, so no input
 * distinguishes the two — that single ConditionalsBoundary mutant is intentionally not killed here.
 */
class RestartBackoffSpec extends Specification {

    private static final Duration BASE = Duration.ofMinutes(1)

    // FR4, D5: the first backoff since the last clean tick is the base interval, each further
    // consecutive failure doubles the previous, capped at MAX_BACKOFF (10 min) — 16 min is clamped.
    def "nextBackoff starts at the base interval, doubles each consecutive failure, then caps"() {
        given:
        def backoff = new RestartBackoff()

        expect:
        backoff.nextBackoff(BASE) == Duration.ofMinutes(1)
        backoff.nextBackoff(BASE) == Duration.ofMinutes(2)
        backoff.nextBackoff(BASE) == Duration.ofMinutes(4)
        backoff.nextBackoff(BASE) == Duration.ofMinutes(8)
        backoff.nextBackoff(BASE) == RestartBackoff.MAX_BACKOFF
        backoff.nextBackoff(BASE) == RestartBackoff.MAX_BACKOFF
    }

    // FR4, D5: MAX_BACKOFF is the ten-minute ceiling the cap clamps to.
    def "the cap is ten minutes"() {
        expect:
        RestartBackoff.MAX_BACKOFF == Duration.ofMinutes(10)
    }

    // FR4, D5: a clean tick resets the consecutive-failure count, so the next death backs off from
    // the base interval again rather than continuing to double.
    def "markCleanTick resets the consecutive-failure count so the next backoff restarts at the base"() {
        given:
        def backoff = new RestartBackoff()
        backoff.nextBackoff(BASE)
        backoff.nextBackoff(BASE)
        backoff.nextBackoff(BASE)

        when:
        backoff.markCleanTick()

        then:
        backoff.nextBackoff(BASE) == Duration.ofMinutes(1)
    }

    // FR4, NFR-O1, UX2: nextRestartCount is a strictly increasing lifetime total starting at 1 that
    // markCleanTick never resets — the ERROR log always carries a distinct, countable restart number.
    def "nextRestartCount increments a lifetime counter that markCleanTick never resets"() {
        given:
        def backoff = new RestartBackoff()

        expect:
        backoff.nextRestartCount() == 1
        backoff.nextRestartCount() == 2

        when:
        backoff.markCleanTick()

        then:
        backoff.nextRestartCount() == 3
    }

    // FR4: restartCount() reads the lifetime total without advancing it (task 2.5's vitals reader).
    def "restartCount reads the lifetime count without incrementing it"() {
        given:
        def backoff = new RestartBackoff()
        backoff.nextRestartCount()
        backoff.nextRestartCount()

        expect:
        backoff.restartCount() == 2
        backoff.restartCount() == 2
    }
}
