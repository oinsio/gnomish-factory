package com.github.oinsio.gnomish.usage.json

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.app.port.git.UsageRow
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.UsageFold
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.domain.engine.fake.Unpriced
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR14, NFR-C1 of add-git-workflow: a ROW's own executor entry's {@code wallMillis} in {@code
 * gnomish usage --json} (the {@code transform} entry of the row's {@code usage} list, FR15 of
 * define-executor-contract). {@code UsageReportJsonMapperSpec} asserts the summed {@code
 * totals.wallMillis}, which is rendered by a different overload; this spec covers the per-attempt
 * one, whose job is to report an executor's measured wall time in milliseconds and to leave the
 * key out — never a zero — when the executor reported no wall time at all: an entry's {@code
 * wallMillis} is optional in the one usage wire vocabulary (status-report v1, "A usage object
 * carries wall time, tools and tokens by model").
 *
 * Added by task 8.7 of split-into-modules (design D13(c)): the distinction between "took no
 * measurable time" and "did not report" is a cost-reporting fact (NFR-C1), so it carries its own
 * spec rather than being inferred from the totals.
 */
class UsageReportExecutorWallTimeSpec extends Specification {

    def mapper = new UsageReportJsonMapper()
    def reader = new ObjectMapper()

    private static UsageRow row(Duration wallTime) {
        def executorUsage = new ExecutorUsage(wallTime, [], Unpriced.of(['claude-x': new TokenUsage(1, 1, 0, 0)]))
        def attempt = AttemptRecords.of(0, AttemptRecord.Result.PASSED,
                Instant.parse('2026-07-18T09:00:00Z'), [], executorUsage, [], Stop.none())
        new UsageRow(attempt)
    }

    private executorNode(Duration wallTime) {
        def one = row(wallTime)
        reader.readTree(mapper.serialize('PROJ-1', [one], UsageFold.foldAll([one.attempt()])))
        .get('rows')[0]
        .get('usage')[0]
    }

    // FR14, NFR-C1: a measured wall time is reported in milliseconds on the row itself, at the
    // granularity the attempt recorded it — not only in the summed totals.
    def "renders a measured executor wall time in milliseconds on the row"() {
        expect:
        executorNode(Duration.ofMillis(1500)).get('wallMillis').asLong() == 1500L
    }

    // NFR-C1: "the executor reported no wall time" is a distinct fact from "it took 0 ms", so an
    // absent duration leaves the optional key out rather than writing a zero.
    def "renders an unreported executor wall time as an absent key, not zero"() {
        given:
        def node = executorNode(null)

        expect:
        !node.has('wallMillis')
    }

    // NFR-C1: the zero case is what proves the absent key above means "unreported" — a genuinely
    // instantaneous executor still renders a number.
    def "renders a zero-length executor wall time as the number 0"() {
        expect:
        executorNode(Duration.ZERO).get('wallMillis').asLong() == 0L
    }
}
