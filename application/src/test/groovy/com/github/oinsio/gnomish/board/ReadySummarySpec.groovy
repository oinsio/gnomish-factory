package com.github.oinsio.gnomish.board

import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import java.time.Instant
import spock.lang.Specification

/**
 * ReadySummary: the Ready column's reconciled FR3 summary — queued,
 * eligible-now, and the per-reason ineligible breakdown, tallied from the
 * Ready column's rows so the parts always sum to the total. Implements FR3
 * of add-board-command.
 */
class ReadySummarySpec extends Specification {

    private static final EligibilityReason BACKOFF = new EligibilityReason.InBackoff(Instant.parse('2026-08-05T09:05:00Z'))
    private static final EligibilityReason FINISHED = new EligibilityReason.Finished()
    private static final EligibilityReason WIP_HELD = new EligibilityReason.WipHeld()

    private static ReadyRow row(String id, EligibilityReason reason = null, boolean returned = false) {
        new ReadyRow(new TaskRef(id), "title-$id", returned, reason)
    }

    // FR3: "Summary counts reconcile" scenario — 7 queued, 3 eligible, 2 backoff, 1 finished, 1 WIP-held
    def "tallies the spec's reconciliation scenario"() {
        given: 'seven ready rows matching the spec scenario exactly'
        def rows = [
            row('github:o/r#1'),
            row('github:o/r#2'),
            row('github:o/r#3'),
            row('github:o/r#4', BACKOFF),
            row('github:o/r#5', BACKOFF),
            row('github:o/r#6', FINISHED),
            row('github:o/r#7', WIP_HELD)
        ]

        expect: 'the tally reconciles exactly as the spec states'
        ReadySummary.tally(rows) == new ReadySummary(7, 3, 2, 1, 1)
    }

    // FR3: every row eligible
    def "tallies an all-eligible ready window"() {
        expect:
        ReadySummary.tally([
            row('github:o/r#1'),
            row('github:o/r#2')
        ]) == new ReadySummary(2, 2, 0, 0, 0)
    }

    // FR3: every row shares the same ineligible reason
    def "tallies a ready window where every row shares one reason"() {
        expect:
        ReadySummary.tally([
            row('github:o/r#1', FINISHED),
            row('github:o/r#2', FINISHED)
        ]) ==
        new ReadySummary(2, 0, 0, 2, 0)
    }

    // FR3: an empty ready window tallies to all zeros
    def "tallies an empty ready window to all zeros"() {
        expect:
        ReadySummary.tally([]) == new ReadySummary(0, 0, 0, 0, 0)
    }

    // FR3: the returned/fresh distinction does not affect the tally bucket, only eligibilityReason does
    def "tallies a returned eligible row as eligible now"() {
        expect:
        ReadySummary.tally([
            row('github:o/r#1', null, true)
        ]) == new ReadySummary(1, 1, 0, 0, 0)
    }

    // FR3: the invariant is enforced defensively even on direct construction
    def "rejects counts that do not reconcile to queuedCount"() {
        when:
        new ReadySummary(7, 3, 2, 1, 0)

        then:
        thrown(IllegalArgumentException)
    }

    // FR3: negative counts are rejected regardless of reconciliation
    def "rejects a negative count"() {
        when:
        new ReadySummary(queuedCount, eligibleNowCount, inBackoffCount, finishedCount, wipHeldCount)

        then:
        thrown(IllegalArgumentException)

        where:
        queuedCount | eligibleNowCount | inBackoffCount | finishedCount | wipHeldCount
        -1 | 0 | 0 | 0 | 0
        0 | -1 | 1 | 0 | 0
        0 | 0 | -1 | 1 | 0
        0 | 0 | 0 | -1 | 1
        -1 | 0 | 0 | 0 | -1
    }
}
