package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import spock.lang.Specification

/**
 * FR1 of add-claim-heartbeat, FR7 of add-serve-observability: {@link HeldClaims}, the heartbeat's
 * held-claim state machine. These specs pin {@link HeldClaims#stopIfEmpty} both ways — it returns
 * {@code true} and clears {@code running} exactly when the set has emptied, and {@code false}
 * leaving the set untouched while any claim remains. Both outcomes are asserted directly so neither
 * the {@code true} nor the {@code false} return can be flipped without a red test.
 */
class HeldClaimsSpec extends Specification {

    private static final TaskRef A = new TaskRef('github:o/r#1')

    private static final Runnable NO_OP_LOOP = { } as Runnable
    private static final Thread.UncaughtExceptionHandler NO_OP_DEATH =
    { Thread t, Throwable e -> } as Thread.UncaughtExceptionHandler

    // FR7: an empty holder has nothing to beat — stopIfEmpty() reports it stopped (true) and leaves
    // the worker IDLE. Asserts the true return directly (kills the return-value mutant on the empty
    // branch).
    def "stopIfEmpty returns true when no claim remains"() {
        given:
        def held = new HeldClaims()

        when:
        def stopped = held.stopIfEmpty()

        then:
        stopped
        held.state() == HeartbeatWorkerState.IDLE
    }

    // FR1, FR7: after a real worker started (RUNNING) and its only claim was removed, stopIfEmpty()
    // returns true AND clears running, flipping the reported state RUNNING -> IDLE — the observable
    // side effect the caller relies on to fire the RUNNING -> IDLE trigger outside the lock.
    def "stopIfEmpty stops a running worker and flips the state to IDLE"() {
        given: 'a worker started for one claim, then that claim removed'
        def held = new HeldClaims()
        held.registerAndMaybeStart(A, NO_OP_LOOP, NO_OP_DEATH)
        assert held.state() == HeartbeatWorkerState.RUNNING
        held.remove(A)

        when:
        def stopped = held.stopIfEmpty()

        then:
        stopped
        held.state() == HeartbeatWorkerState.IDLE
    }

    // FR1: registerAndMaybeStart returns true on the call that actually starts the worker and false
    // on a later call while one is already running. Asserts BOTH return directions directly (kills
    // both return-value mutants) with a no-op loop body so no real beat work runs.
    def "registerAndMaybeStart starts once and reports false while already running"() {
        given:
        def held = new HeldClaims()

        when: 'the first register starts the worker'
        def started = held.registerAndMaybeStart(A, NO_OP_LOOP, NO_OP_DEATH)

        then:
        started
        held.state() == HeartbeatWorkerState.RUNNING

        when: 'a second register arrives while the worker is already running'
        def secondStarted = held.registerAndMaybeStart(new TaskRef('github:o/r#2'), NO_OP_LOOP, NO_OP_DEATH)

        then:
        !secondStarted
        held.count() == 2
    }

    // FR7: while a claim is still held, stopIfEmpty() reports NOT stopped (false) and leaves the set
    // intact. Asserts the false return directly (kills the return-value mutant on the non-empty
    // branch), which the true-return specs above cannot reach.
    def "stopIfEmpty returns false and keeps the claim while any remains"() {
        given:
        def held = new HeldClaims()
        held.seed(A)

        when:
        def stopped = held.stopIfEmpty()

        then:
        !stopped
        held.count() == 1
    }
}
