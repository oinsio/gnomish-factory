package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import java.util.function.IntSupplier
import spock.lang.Specification

/**
 * OpenFrontGate: the per-claim open-front re-check (design D5) that a claim
 * loop (task 2.3) consults immediately before attempting each candidate's
 * claim — fresh candidates re-read the open-front count on demand, returned
 * candidates skip the check entirely (FR7), and the per-claim discipline
 * bounds overshoot to one fresh task per racing instance (M4).
 *
 * Implements FR6, D5, M4 of add-factory-serve.
 */
class OpenFrontGateSpec extends Specification {

    private static ReadyTask fresh(String id) {
        new ReadyTask(new TaskRef(id), AbortFacts.none(), false, false, 'fixture title')
    }

    private static ReadyTask returned(String id) {
        new ReadyTask(new TaskRef(id), AbortFacts.none(), true, false, 'fixture title')
    }

    // FR6, D5: a fresh candidate is claimable while the re-read count is below the limit
    def "a fresh candidate is still eligible when the re-read open-front count is below the limit"() {
        given:
        def candidate = fresh('F1')
        IntSupplier supplier = { -> 3 } as IntSupplier

        expect:
        OpenFrontGate.isStillEligible(candidate, supplier, 10)
    }

    // FR6, D5: a fresh candidate is excluded once the re-read count has reached the limit
    def "a fresh candidate is not eligible when the re-read open-front count has reached the limit"() {
        given:
        def candidate = fresh('F1')
        IntSupplier supplier = { -> 10 } as IntSupplier

        expect:
        !OpenFrontGate.isStillEligible(candidate, supplier, 10)
    }

    // FR7: a returned candidate is always eligible, regardless of the open-front count
    def "a returned candidate is eligible regardless of the open-front count"() {
        given:
        def candidate = returned('R1')
        IntSupplier supplier = { -> 999 } as IntSupplier

        expect:
        OpenFrontGate.isStillEligible(candidate, supplier, 10)
    }

    // FR7: the count supplier is never invoked for a returned candidate - the check is skipped entirely
    def "the open-front count supplier is not invoked for a returned candidate"() {
        given:
        def candidate = returned('R1')
        def supplier = Mock(IntSupplier)

        when:
        def eligible = OpenFrontGate.isStillEligible(candidate, supplier, 10)

        then:
        eligible
        0 * supplier.getAsInt()
    }

    // FR6, D5: the count supplier is invoked exactly once per fresh candidate check
    def "the open-front count supplier is invoked exactly once for a fresh candidate"() {
        given:
        def candidate = fresh('F1')
        def supplier = Mock(IntSupplier)

        when:
        OpenFrontGate.isStillEligible(candidate, supplier, 10)

        then:
        1 * supplier.getAsInt() >> 0
    }

    // M4: overshoot-bounded property - the worst-case race window is M instances all
    // reading the same stale open-front count before any of their claims has landed
    // (design D5's re-check narrows this window per attempt, but cannot close a
    // genuinely concurrent read-then-write race). Deterministic simulation of the
    // logic's bound, not a concurrency/thread-safety test.
    def "M racing instances that all observe the same stale snapshot overshoot by at most M"() {
        given: 'one open slot when every racing instance takes its stale reading'
        int wipLimit = 10
        int racingInstances = 4
        int staleSnapshot = wipLimit - 1
        IntSupplier frozenSupplier = { -> staleSnapshot } as IntSupplier

        when: 'each instance independently re-checks against the same frozen snapshot'
        def eligibleCount = (1..racingInstances).count { i ->
            OpenFrontGate.isStillEligible(fresh("F$i"), frozenSupplier, wipLimit)
        }
        int finalCount = staleSnapshot + eligibleCount

        then: 'worst case every instance sees the stale under-limit count and claims'
        eligibleCount == racingInstances
        finalCount == wipLimit - 1 + racingInstances
        finalCount <= wipLimit + racingInstances
    }

    // M4: with the count incorporating each prior claim (as tracker.listOpen() would after a
    // real claim lands), overshoot never exceeds one task per racing instance in aggregate.
    def "overshoot never exceeds W + M when the shared counter reflects each successful claim as it happens"() {
        given: 'a queue with far more fresh candidates than any single instance could claim'
        int wipLimit = 10
        int racingInstances = 5
        int[] sharedOpenFrontCount = [0]
        IntSupplier sharedSupplier = { -> sharedOpenFrontCount[0] } as IntSupplier

        when: 'simulate many claim rounds; each instance re-checks before every claim attempt'
        int claimed = 0
        (racingInstances * 20).times {
            def candidate = fresh("F$it")
            if (OpenFrontGate.isStillEligible(candidate, sharedSupplier, wipLimit)) {
                sharedOpenFrontCount[0]++
                claimed++
            }
        }

        then: 'the shared counter never exceeds W, since every check observes the live count'
        sharedOpenFrontCount[0] == wipLimit
        claimed == wipLimit
    }
}
