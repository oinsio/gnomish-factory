package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FeedPolicy: the shared claim-eligibility policy for serve and bare auto
 * take (design D2) — backoff filtering delegated to BackoffPolicy,
 * returned-tasks-first / WIP-gated-fresh ordering (FR6), and the head-zone
 * pick over the first K = 5 eligible entries (FR9, D4).
 *
 * Implements FR6, FR9, D2, D4 of add-factory-serve.
 */
class FeedPolicySpec extends Specification {

    private static final Duration BASE = Duration.ofMinutes(2)
    private static final Duration CAP = Duration.ofHours(1)
    private static final Instant NOW = Instant.parse('2026-07-31T10:00:00Z')

    /** Random stub that always returns a fixed index, for deterministic head-zone assertions. */
    private static Random fixedPick(int index) {
        new Random() {
                    @Override
                    int nextInt(int bound) {
                        assert index < bound
                        return index
                    }
                }
    }

    private static ReadyTask fresh(String id) {
        new ReadyTask(new TaskRef(id), AbortFacts.none(), false)
    }

    private static ReadyTask returned(String id) {
        new ReadyTask(new TaskRef(id), AbortFacts.none(), true)
    }

    private static ReadyTask backedOff(String id) {
        new ReadyTask(new TaskRef(id), new AbortFacts(1, NOW.minus(Duration.ofMinutes(1))), false)
    }

    // FR10, D10 (delegate correctness): backed-off entries never appear among candidates
    def "backoff filtering still applies before eligibility ordering"() {
        given:
        def tasks = [
            fresh('A'),
            backedOff('B'),
            fresh('C')
        ]

        when:
        def candidates = FeedPolicy.selectClaimCandidates(tasks, BASE, CAP, NOW, 0, 10, fixedPick(0))

        then:
        candidates*.ref()*.id() as Set == ['A', 'C'] as Set
        !candidates*.ref()*.id().contains('B')
    }

    // FR6: returned tasks are ordered ahead of fresh tasks
    def "returned tasks are ordered ahead of fresh tasks"() {
        given: 'fresh listed first in adapter queue order, returned listed after'
        def tasks = [
            fresh('F1'),
            returned('R1'),
            fresh('F2'),
            returned('R2')
        ]

        when: 'head-zone pick draws index 0 so ordering is directly observable'
        def candidates = FeedPolicy.selectClaimCandidates(tasks, BASE, CAP, NOW, 0, 10, fixedPick(0))

        then:
        candidates*.ref()*.id() == ['R1', 'R2', 'F1', 'F2']
    }

    // FR6: a fresh task is excluded once open fronts reach the WIP limit
    def "a fresh task is excluded when openFrontCount reaches the WIP limit"() {
        given:
        def tasks = [fresh('F1')]

        when:
        def candidates = FeedPolicy.selectClaimCandidates(tasks, BASE, CAP, NOW, 10, 10, fixedPick(0))

        then:
        candidates == []
    }

    // FR6: a returned task remains eligible regardless of the WIP state
    def "a returned task is included regardless of the WIP state"() {
        given:
        def tasks = [returned('R1')]

        when:
        def candidates = FeedPolicy.selectClaimCandidates(tasks, BASE, CAP, NOW, 10, 10, fixedPick(0))

        then:
        candidates*.ref()*.id() == ['R1']
    }

    // FR9, D4: the head-zone pick draws from among the first K = 5 eligible entries,
    // not always the strict head, and the remainder keeps its original relative order
    def "head-zone pick draws the configured index and preserves order for the rest"() {
        given: 'six eligible fresh tasks, more than K = 5'
        def tasks = (1..6).collect { fresh("F$it") }

        when: 'a fixed random draws index 3 (0-based) within the K=5 zone'
        def candidates = FeedPolicy.selectClaimCandidates(tasks, BASE, CAP, NOW, 0, 10, fixedPick(3))

        then: 'F4 (index 3) is drawn to the front; the rest follow in original order'
        candidates*.ref()*.id() == [
            'F4',
            'F1',
            'F2',
            'F3',
            'F5',
            'F6'
        ]
    }

    // FR9, D4: K=5 boundary - when eligible size <= K, every entry is a head-zone candidate
    def "head-zone pick draws from the full list when eligible size is at or below K"() {
        given: 'exactly K = 5 eligible fresh tasks'
        def tasks = (1..5).collect { fresh("F$it") }

        when: 'a fixed random draws the last index, index 4, still within bounds'
        def candidates = FeedPolicy.selectClaimCandidates(tasks, BASE, CAP, NOW, 0, 10, fixedPick(4))

        then:
        candidates*.ref()*.id() == ['F5', 'F1', 'F2', 'F3', 'F4']
    }

    // FR9, D4: head-zone width never exceeds K = 5 even with many more eligible entries
    def "head-zone pick never draws beyond index K-1 even with far more eligible entries"() {
        given: 'ten eligible fresh tasks; nextInt(bound) asserts bound <= K = 5'
        def tasks = (1..10).collect { fresh("F$it") }

        when:
        FeedPolicy.selectClaimCandidates(tasks, BASE, CAP, NOW, 0, 10, fixedPick(4))

        then:
        noExceptionThrown()
    }

    // Edge case: an empty ready list yields no candidates
    def "an empty ready list yields no candidates"() {
        expect:
        FeedPolicy.selectClaimCandidates([], BASE, CAP, NOW, 0, 10, fixedPick(0)) == []
    }

    // Edge case: every entry backed off yields no candidates
    def "all tasks backed off yields no candidates"() {
        given:
        def tasks = [
            backedOff('A'),
            backedOff('B')
        ]

        expect:
        FeedPolicy.selectClaimCandidates(tasks, BASE, CAP, NOW, 0, 10, fixedPick(0)) == []
    }

    // Edge case: WIP limit blocks every fresh task, only returned tasks remain
    def "when all fresh tasks are WIP-blocked only returned tasks remain"() {
        given:
        def tasks = [
            fresh('F1'),
            returned('R1'),
            fresh('F2')
        ]

        when:
        def candidates = FeedPolicy.selectClaimCandidates(tasks, BASE, CAP, NOW, 10, 10, fixedPick(0))

        then:
        candidates*.ref()*.id() == ['R1']
    }

    // Edge case: all tasks returned - all eligible regardless of WIP, order preserved pre-pick
    def "all tasks returned are all eligible regardless of the WIP state"() {
        given:
        def tasks = [
            returned('R1'),
            returned('R2'),
            returned('R3')
        ]

        when:
        def candidates = FeedPolicy.selectClaimCandidates(tasks, BASE, CAP, NOW, 10, 10, fixedPick(1))

        then:
        candidates*.ref()*.id() == ['R2', 'R1', 'R3']
    }
}
