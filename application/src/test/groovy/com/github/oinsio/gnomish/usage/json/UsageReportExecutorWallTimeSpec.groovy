package com.github.oinsio.gnomish.usage.json

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.app.port.git.UsageRow
import com.github.oinsio.gnomish.app.port.git.UsageTotals
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR14, NFR-C1 of add-git-workflow: a ROW's own {@code executorUsage.wallMillis} in {@code
 * gnomish usage --json}. {@code UsageReportJsonMapperSpec} asserts the summed {@code
 * totals.wallMillis}, which is rendered by a different overload; this spec covers the per-attempt
 * one, whose job is to report an executor's measured wall time in milliseconds and to emit an
 * explicit {@code null} — never a zero — when the executor reported no wall time at all.
 *
 * Added by task 8.7 of split-into-modules (design D13(c)): the distinction between "took no
 * measurable time" and "did not report" is a cost-reporting fact (NFR-C1), so it carries its own
 * spec rather than being inferred from the totals.
 */
class UsageReportExecutorWallTimeSpec extends Specification {

    def mapper = new UsageReportJsonMapper()
    def reader = new ObjectMapper()

    private static UsageRow row(Duration wallTime) {
        def executorUsage = new ExecutorUsage(wallTime, [], ['claude-x': new TokenUsage(1, 1, 0, 0)])
        def attempt = new AttemptRecord(0, AttemptRecord.Result.PASSED,
                Instant.parse('2026-07-18T09:00:00Z'), [], executorUsage, new JudgeUsage([]), [])
        new UsageRow('implement', attempt)
    }

    private executorNode(Duration wallTime) {
        def one = row(wallTime)
        reader.readTree(mapper.serialize('PROJ-1', [one], UsageTotals.of([one])))
        .get('rows')[0]
        .get('executorUsage')
    }

    // FR14, NFR-C1: a measured wall time is reported in milliseconds on the row itself, at the
    // granularity the attempt recorded it — not only in the summed totals.
    def "renders a measured executor wall time in milliseconds on the row"() {
        expect:
        executorNode(Duration.ofMillis(1500)).get('wallMillis').asLong() == 1500L
    }

    // NFR-C1: "the executor reported no wall time" is a distinct fact from "it took 0 ms", so an
    // absent duration renders as an explicit JSON null rather than a zero.
    def "renders an unreported executor wall time as an explicit null, not zero"() {
        given:
        def node = executorNode(null)

        expect:
        node.get('wallMillis').isNull()
        !node.get('wallMillis').isNumber()
    }

    // NFR-C1: the zero case is what proves the null above means "unreported" — a genuinely
    // instantaneous executor still renders a number.
    def "renders a zero-length executor wall time as the number 0"() {
        expect:
        executorNode(Duration.ZERO).get('wallMillis').asLong() == 0L
    }
}
