package com.github.oinsio.gnomish.serveobservability

import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * {@link RunSummaryAccumulator}: the drain run's in-memory {@code runSummary} accumulator
 * (design D6, FR13) — accumulates outcome counts and token sums from each terminal {@link
 * TakeResult}, mirroring {@link TaskOutcomeLineAssembler}'s vocabulary derivation; {@code
 * EmptyQueue}/{@code Skipped} contribute nothing, matching "engine run happened iff spend
 * happened".
 *
 * <p>Implements FR13, D6 of add-serve-observability.
 */
class RunSummaryAccumulatorSpec extends Specification {

    private static TaskState stateAt(ExecutorUsage totals) {
        return new TaskState(new Position.AtStage('build'), 1, [], totals)
    }

    private static ExecutorUsage tokensOf(Map<String, TokenUsage> tokensByModel) {
        return new ExecutorUsage(null, [], tokensByModel)
    }

    def "counts each of the four terminal outcomes and starts at zero"() {
        given:
        def accumulator = new RunSummaryAccumulator()

        expect:
        accumulator.counts() == new OutcomeCounts(0, 0, 0, 0)

        when:
        accumulator.record(new TakeResult.Delivered(stateAt(ExecutorUsage.none()), 'shipped it'))
        accumulator.record(new TakeResult.AwaitingHuman(stateAt(ExecutorUsage.none()), ParkReason.ESCALATION, 'needs a human'))
        accumulator.record(new TakeResult.Aborted(stateAt(ExecutorUsage.none()), 'durability guarantee broke'))
        accumulator.record(new TakeResult.Revoked(stateAt(ExecutorUsage.none()), 'claim lost mid-run'))

        then:
        accumulator.counts() == new OutcomeCounts(1, 1, 1, 1)
    }

    def "sums tokensByModel across multiple recorded outcomes, per model"() {
        given:
        def accumulator = new RunSummaryAccumulator()
        // Every field is non-zero on both sides: a zero addend (e.g. cacheCreation 10 + 0) would
        // make addition and subtraction produce the same result, hiding a PIT
        // addition-replaced-with-subtraction mutant (task 6.3).
        def first = tokensOf(['claude-x': new TokenUsage(100L, 50L, 10L, 5L)])
        def second = tokensOf(['claude-x': new TokenUsage(20L, 5L, 3L, 1L), 'claude-y': new TokenUsage(7L, 3L, 2L, 4L)])

        when:
        accumulator.record(new TakeResult.Delivered(stateAt(first), 'shipped it'))
        accumulator.record(new TakeResult.Delivered(stateAt(second), 'shipped it too'))

        then:
        accumulator.tokensByModel() == [
            'claude-x': new LedgerTokenUsage(120L, 55L, 13L, 6L),
            'claude-y': new LedgerTokenUsage(7L, 3L, 2L, 4L),
        ]
    }

    // D6 / FR13: slots finish concurrently and feed one shared accumulator beside
    // DrainReport, so record() is called from many slot threads at once. The
    // synchronized record/read points must serialize every counter ++ and token
    // merge — with no lost update, the totals are exact. This pins the Javadoc's
    // thread-safety claim: drop `synchronized` and the burst under-counts.
    def "concurrent record() from many threads loses no count or token"() {
        given:
        def accumulator = new RunSummaryAccumulator()
        def threadCount = 20
        def recordsPerThread = 50
        def total = threadCount * recordsPerThread
        ExecutorService pool = Executors.newFixedThreadPool(threadCount)
        def ready = new CountDownLatch(threadCount)
        def go = new CountDownLatch(1)

        when:
        def futures = (0..<threadCount).collect { t ->
            pool.submit({
                ready.countDown()
                go.await()
                (0..<recordsPerThread).each {
                    accumulator.record(new TakeResult.Delivered(
                    stateAt(tokensOf(['claude-x': new TokenUsage(1L, 1L, 1L, 1L)])), 'shipped it'))
                }
            } as Runnable)
        }
        ready.await()
        go.countDown()
        futures.each { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        then:
        accumulator.counts() == new OutcomeCounts(total, 0, 0, 0)
        accumulator.tokensByModel() == ['claude-x': new LedgerTokenUsage(total, total, total, total)]
    }

    def "does not count EmptyQueue or Skipped, and does not add their (nonexistent) tokens"() {
        given:
        def accumulator = new RunSummaryAccumulator()

        when:
        accumulator.record(new TakeResult.EmptyQueue())
        accumulator.record(new TakeResult.Skipped('lost claim race'))

        then:
        accumulator.counts() == new OutcomeCounts(0, 0, 0, 0)
        accumulator.tokensByModel().isEmpty()
    }
}
