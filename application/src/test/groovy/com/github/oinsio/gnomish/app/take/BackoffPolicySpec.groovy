package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.Unroll

/**
 * BackoffPolicy: exponential abort backoff computed by core over
 * adapter-reported AbortFacts (design D10) — delay growth, cap enforcement,
 * the isBackedOff time-window decision, and filterEligible over a listReady
 * fixture.
 *
 * Implements FR10, D10, NFR-C1 of add-tracker-port.
 */
class BackoffPolicySpec extends Specification {

    private static final Duration BASE = Duration.ofMinutes(2)
    private static final Duration CAP = Duration.ofHours(1)
    private static final Instant NOW = Instant.parse('2026-07-23T10:00:00Z')

    // FR10, D10: delay = base * 2^(count-1), uncapped for small counts
    @Unroll
    def "delay grows exponentially: count=#count -> #expected"() {
        expect:
        BackoffPolicy.delay(count, BASE, CAP) == expected

        where:
        count | expected
        1 | BASE
        2 | BASE.multipliedBy(2)
        3 | BASE.multipliedBy(4)
        4 | BASE.multipliedBy(8)
    }

    // D10: count <= 0 means "nothing to back off" - delay is zero
    def "delay is zero for a non-positive count"() {
        expect:
        BackoffPolicy.delay(0, BASE, CAP) == Duration.ZERO
    }

    // NFR-C1: a high count's computed delay is clamped to cap
    def "delay is clamped to the cap for a high count"() {
        expect:
        BackoffPolicy.delay(20, BASE, CAP) == CAP
    }

    // NFR-C1: the exact boundary where the raw exponential delay equals the cap is
    //     returned as-is, uncapped-but-identical — proves the comparison is "> 0", not ">= 0"
    def "delay at the exact boundary where raw equals the cap returns the cap value uncapped"() {
        given: 'base=2m, count=5 -> raw = 2m * 2^4 = 32m; cap set to exactly 32m'
        def base = Duration.ofMinutes(2)
        def cap = Duration.ofMinutes(32)

        expect:
        BackoffPolicy.delay(5, base, cap) == cap
    }

    // D10: isBackedOff is true while now - lastAbortAt < delay
    def "isBackedOff is true when the backoff window has not expired"() {
        given: 'one prior abort (delay = base = 2m), aborted 1 minute ago'
        def facts = new AbortFacts(1, NOW - Duration.ofMinutes(1))

        expect:
        BackoffPolicy.isBackedOff(facts, BASE, CAP, NOW)
    }

    // D10: isBackedOff is false once now - lastAbortAt >= delay
    def "isBackedOff is false once the backoff window has expired"() {
        given: 'one prior abort (delay = base = 2m), aborted 5 minutes ago'
        def facts = new AbortFacts(1, NOW - Duration.ofMinutes(5))

        expect:
        !BackoffPolicy.isBackedOff(facts, BASE, CAP, NOW)
    }

    // D10: count == 0 (AbortFacts.none()) is never backed off, regardless of now
    def "a task with no abort history is never backed off"() {
        expect:
        !BackoffPolicy.isBackedOff(AbortFacts.none(), BASE, CAP, NOW)
    }

    // FR10, D10: filterEligible preserves adapter queue order and drops only
    // backed-off entries
    def "filterEligible preserves order and drops only backed-off entries"() {
        given: 'a mix of fresh, backed-off, and expired-backoff tasks in queue order'
        def fresh = new ReadyTask(new TaskRef('PROJ-1'), AbortFacts.none(), false, false, 'fixture title')
        def backedOff = new ReadyTask(new TaskRef('PROJ-2'), new AbortFacts(1, NOW - Duration.ofMinutes(1)), false, false, 'fixture title')
        def expired = new ReadyTask(new TaskRef('PROJ-3'), new AbortFacts(1, NOW - Duration.ofMinutes(5)), false, false, 'fixture title')
        def tasks = [fresh, backedOff, expired]

        when:
        def eligible = BackoffPolicy.filterEligible(tasks, BASE, CAP, NOW)

        then:
        eligible == [fresh, expired]
    }

    // FR10, D10: an empty listReady result stays empty
    def "filterEligible over an empty list returns an empty list"() {
        expect:
        BackoffPolicy.filterEligible([], BASE, CAP, NOW) == []
    }
}
