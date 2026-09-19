package com.github.oinsio.gnomish.serveobservability

import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import java.time.Instant
import spock.lang.Specification

/**
 * {@link RunSummaryLineAssembler}: the pure mapping from a completed drain run's {@link
 * RunSummaryAccumulator} totals to a {@link RunSummaryLine} (design D6, FR13) — reads the
 * accumulator once, never the ledger (design D5).
 *
 * <p>Implements FR13, D6 of add-serve-observability.
 */
class RunSummaryLineAssemblerSpec extends Specification {

    private static final InstanceInfo INSTANCE = new InstanceInfo('gnomish-x7k2q1', 'worker-1', '0.1.0')
    private static final Instant STARTED_AT = Instant.parse('2026-08-03T10:00:00Z')
    private static final Instant FINISHED_AT = Instant.parse('2026-08-03T10:05:30Z')

    def "assembles a RunSummaryLine from the accumulator's current counts and token sums"() {
        given:
        def accumulator = new RunSummaryAccumulator()
        def finalState = new TaskState(
                new Position.AtStage('build'), 0, [],
                new ExecutorUsage(null, [], ['claude-x': new TokenUsage(100L, 50L, 10L, 5L)]))
        accumulator.record(new TakeResult.Delivered(finalState, 'shipped it'))

        when:
        def line = RunSummaryLineAssembler.assemble(INSTANCE, STARTED_AT, FINISHED_AT, accumulator)

        then:
        line == new RunSummaryLine(
                INSTANCE, STARTED_AT, FINISHED_AT, 330_000L,
                new OutcomeCounts(1, 0, 0, 0),
                ['claude-x': new LedgerTokenUsage(100L, 50L, 10L, 5L)])
    }

    def "assembles a zero-outcome line for a run with nothing recorded"() {
        given:
        def accumulator = new RunSummaryAccumulator()

        when:
        def line = RunSummaryLineAssembler.assemble(INSTANCE, STARTED_AT, FINISHED_AT, accumulator)

        then:
        line.counts() == new OutcomeCounts(0, 0, 0, 0)
        line.tokensByModel().isEmpty()
        line.wallMillis() == 330_000L
    }
}
