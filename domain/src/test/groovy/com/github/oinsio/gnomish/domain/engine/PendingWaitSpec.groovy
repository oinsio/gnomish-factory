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
        def wait = new PendingWait(time, check(), since)

        expect:
        wait.elapsed() == Duration.ofMinutes(2)
        wait.remaining() == Duration.ofMinutes(8)
    }

    def "FR2: a hint of #hint waits #waited and lets the next poll come"() {
        given:
        def wait = new PendingWait(time, check(), clock.instant())

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
        def wait = new PendingWait(time, check(), clock.instant())

        when:
        def polls = wait.waited(pending(TIMEOUT.plusSeconds(1)))

        then:
        !polls
        sleeper.slept == [TIMEOUT]
        wait.remaining() == Duration.ZERO
    }

    def "FR4: no time left ends the wait without sleeping"() {
        given: 'an entry whose timeout already elapsed'
        def wait = new PendingWait(time, check(), clock.instant().minus(TIMEOUT))

        when:
        def polls = wait.waited(pending(null))

        then:
        !polls
        sleeper.slept.isEmpty()
    }
}
