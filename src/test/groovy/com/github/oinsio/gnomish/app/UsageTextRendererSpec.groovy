package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.UsageRow
import com.github.oinsio.gnomish.adapter.git.UsageTotals
import com.github.oinsio.gnomish.adapter.git.state.StateAttemptDto
import com.github.oinsio.gnomish.adapter.git.state.StateJudgeUsageDto
import com.github.oinsio.gnomish.adapter.git.state.StateTokenUsageDto
import com.github.oinsio.gnomish.adapter.git.state.StateUsageDto
import spock.lang.Specification

/**
 * FR14, NFR-C1 of add-git-workflow: {@code gnomish usage}'s text mode renders a stage/round table
 * with tokens summed across models and wall time, plus a totals line — built directly from
 * in-memory {@link UsageRow}/{@link UsageTotals} values (no git fixture needed for pure
 * rendering).
 */
class UsageTextRendererSpec extends Specification {

    def renderer = new UsageTextRenderer()

    private static StateAttemptDto attempt(int round, String result, Long wall, Map<String, StateTokenUsageDto> tokens) {
        new StateAttemptDto(round, result, '2026-07-18T09:00:00Z', [],
        new StateUsageDto(wall, tokens, []), new StateJudgeUsageDto([]))
    }

    def "FR14: renders one row per round with summed tokens and wall time, plus a totals line"() {
        given:
        def row0 = new UsageRow('implement', attempt(0, 'qualityFailure', 1000,
                ['claude-x': new StateTokenUsageDto(100, 10, 1, 2)]))
        def row1 = new UsageRow('implement', attempt(1, 'passed', 2000,
                ['claude-x': new StateTokenUsageDto(200, 20, 3, 4), 'claude-y': new StateTokenUsageDto(50, 5, 5, 6)]))
        def rows = [row0, row1]
        def totals = UsageTotals.of(rows)

        when:
        def output = renderer.render(rows, totals)

        then:
        output.contains('implement')
        output.contains('qualityFailure')
        output.contains('passed')

        and: 'row 0 shows its own summed tokens, cache (cacheCreation+cacheRead=1+2=3), and wall time'
        def lines = output.readLines()
        lines[1].contains('100') && lines[1].contains('10') && lines[1].contains('1000')
        lines[1].split(/\s+/).contains('3')

        and: 'row 1 sums across both models (200+50 in, 20+5 out, cache (3+4)+(5+6)=18)'
        lines[2].contains('250') && lines[2].contains('25')
        lines[2].split(/\s+/).contains('18')

        and: 'the totals line sums wall time and tokens across all rows, cache (3+18)=21'
        def totalLine = lines.last()
        totalLine.contains('TOTAL')
        totalLine.contains('in=350')
        totalLine.contains('out=35')
        totalLine.contains('cache=21')
        totalLine.contains('wallMillis=3000')
    }

    def "FR14: an empty history renders 'no rounds recorded' rather than an empty table"() {
        when:
        def output = renderer.render([], UsageTotals.empty())

        then:
        output == 'no rounds recorded'
    }

    def "FR14: a row with no reported wall time renders a placeholder, not a crash"() {
        given:
        def row = new UsageRow('implement', attempt(0, 'passed', null as Long, [:]))

        when:
        def output = renderer.render([row], UsageTotals.of([row]))

        then:
        noExceptionThrown()
        output.contains('-')
    }

    // PIT EmptyObjectReturnValsMutator on UsageTotals#mergeWall's `return left;` (right null, left
    // non-null): a later row with no reported wall time must not reset an already-accumulated
    // total back to zero/unknown.
    def "FR14: a later row with no wall time does not erase an already-accumulated total"() {
        given:
        def rowWithWall = new UsageRow('implement', attempt(0, 'passed', 1000, [:]))
        def rowWithoutWall = new UsageRow('verify', attempt(0, 'passed', null as Long, [:]))

        when:
        def totals = UsageTotals.of([rowWithWall, rowWithoutWall])

        then:
        totals.wallMillis() == 1000L
    }
}
