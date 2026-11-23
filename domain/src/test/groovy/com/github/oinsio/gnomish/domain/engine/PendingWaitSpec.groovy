package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.BudgetedVirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.pendingkey.PendingKey
import java.time.Duration
import spock.lang.Specification

/**
 * FR2, FR4 of migrate-verify-to-executor-contract (design D2, D19): the clock of one check's wait —
 * the deadline at {@code since} plus the timeout, elapsed and remaining time on the injected clock,
 * and the wait before the next poll. On virtual time.
 */
class PendingWaitSpec extends Specification {

    static final Duration INTERVAL = Duration.ofSeconds(30)
    static final Duration TIMEOUT = Duration.ofMinutes(10)

    def clock = new VirtualClock()
    def sleeper = new BudgetedVirtualSleeper(clock, 20)
    def time = VirtualTimeEquipment.on(clock, sleeper)

    static VerifyCheck.Executor check() {
        VerifyChecks.external('ci', 'scm-status', INTERVAL, TIMEOUT, VerifyCheck.TimeoutClass.QUALITY)
    }

    static ExecutionResult.Pending pending(Duration hint) {
        new ExecutionResult.Pending(new PendingKey('k-1'), hint, PendingState.RUNNING, null, null)
    }

    def "FR4: the deadline is since plus the timeout, measured on the injected clock"() {
        given: 'an entry recorded two minutes before now'
        def since = clock.instant().minus(Duration.ofMinutes(2))
        def wait = PendingWait.ofCheck(time, check(), since)

        expect:
        wait.elapsed() == Duration.ofMinutes(2)
        wait.remaining() == Duration.ofMinutes(8)
    }

    def "FR2: a hint of #hint waits #waited and lets the next poll come"() {
        given:
        def wait = PendingWait.ofCheck(time, check(), clock.instant())

        when:
        def polls = wait.waited(pending(hint))

        then:
        polls
        sleeper.slept == [waited]
        wait.elapsed() == waited

        where:
        hint || waited
        null || INTERVAL
        Duration.ofSeconds(10) || INTERVAL
        INTERVAL || INTERVAL
        Duration.ofMinutes(2) || Duration.ofMinutes(2)
        TIMEOUT || TIMEOUT
    }

    def "FR2: a hint past the deadline sleeps to the deadline and ends the wait"() {
        given:
        def wait = PendingWait.ofCheck(time, check(), clock.instant())

        when:
        def polls = wait.waited(pending(TIMEOUT.plusSeconds(1)))

        then:
        !polls
        sleeper.slept == [TIMEOUT]
        wait.remaining() == Duration.ZERO
    }

    def "FR4: no time left ends the wait without sleeping"() {
        given: 'an entry whose timeout already elapsed'
        def wait = PendingWait.ofCheck(time, check(), clock.instant().minus(TIMEOUT))

        when:
        def polls = wait.waited(pending(null))

        then:
        !polls
        sleeper.slept.isEmpty()
    }

    def "FR10 of extract-agent-round-runner: a round's wait paces max(pending interval, hint) and ends at the ceiling from since"() {
        given: 'the factory bounds: a 1-minute interval, a 1-hour ceiling; an entry recorded 20 minutes ago'
        def bounds = new PendingBounds(Duration.ofMinutes(1), Duration.ofHours(1))
        def wait = PendingWait.ofRound(time, bounds, clock.instant().minus(Duration.ofMinutes(20)))

        expect: 'the ceiling is counted from since, not from now'
        wait.elapsed() == Duration.ofMinutes(20)
        wait.remaining() == Duration.ofMinutes(40)

        when: 'a short hint and a long hint'
        def first = wait.waited(pending(Duration.ofSeconds(5)))
        def second = wait.waited(pending(Duration.ofMinutes(3)))

        then: 'the pace is the larger of the two, never below the interval'
        first && second
        sleeper.slept == [
            Duration.ofMinutes(1),
            Duration.ofMinutes(3)
        ]

        when: 'a hint reaching past the ceiling'
        def third = wait.waited(pending(Duration.ofHours(2)))

        then: 'the wait ends at the ceiling with no further poll'
        !third
        wait.remaining() == Duration.ZERO
        wait.elapsed() == Duration.ofHours(1)
    }
}
