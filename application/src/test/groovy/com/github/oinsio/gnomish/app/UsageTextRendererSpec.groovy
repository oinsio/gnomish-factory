package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.UsageRow
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Participants
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.UsageFold
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.domain.engine.fake.Unpriced
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR14, NFR-C1 of add-git-workflow; FR17 of define-executor-contract: {@code gnomish usage}'s text
 * mode renders a stage/round table with tokens summed over participants and models by the one usage
 * fold and the executor's wall time, followed by the snapshot — built directly from in-memory
 * {@link UsageRow} values and their fold (no git fixture needed for pure rendering).
 */
class UsageTextRendererSpec extends Specification {

    def renderer = new UsageTextRenderer()

    private static AttemptRecord attempt(int round, String result, Long wall, Map<String, TokenUsage> tokens) {
        attempt(round, result, [
            Participants.transform(AttemptRecords.EXECUTOR,
            new ExecutorUsage(wall == null ? null : Duration.ofMillis(wall), [], Unpriced.of(tokens)))
        ])
    }

    /** A round opened on a tip of its own, as every branch-mode round is. */
    private static AttemptRecord attempt(int round, String result, List usage, String stage = 'implement') {
        AttemptRecords.identified(AttemptRecords.TASK_ID, stage,
                RoundToken.of(String.format('%040x', round + 1)), round,
                AttemptRecord.Result.valueOf(RESULTS[result]), Instant.parse('2026-07-18T09:00:00Z'), [], usage, [], Stop.none())
    }

    private static UsageSnapshot fold(List<UsageRow> rows) {
        UsageFold.foldAll(rows*.attempt())
    }

    private static final Map<String, String> RESULTS = [
        passed: 'PASSED',
        qualityFailure: 'QUALITY_FAILURE',
        cannotVerify: 'CANNOT_VERIFY',
        decisionNeeded: 'DECISION_NEEDED',
        budgetExhausted: 'BUDGET_EXHAUSTED']

    // FR7 of define-executor-contract: every result renders as its state.json token, over the
    //     whole constant set
    def "renders every round result as its lowerCamel token: #token"() {
        given:
        def rows = [
            new UsageRow(attempt(0, token, 1000, [:]))
        ]

        expect:
        renderer.render(rows, fold(rows)).readLines()[1].contains(token)

        where:
        token << RESULTS.keySet().toList()
    }

    def "the token table names every result"() {
        expect:
        RESULTS.values() as Set == AttemptRecord.Result.values()*.name() as Set
    }

    // Scenario: cli/task-inspection / Text usage is a stage and round table with totals
    def "FR14: renders one row per round with summed tokens and wall time, plus a totals line"() {
        given:
        def row0 = new UsageRow(attempt(0, 'qualityFailure', 1000,
                ['claude-x': new TokenUsage(100, 10, 1, 2)]))
        def row1 = new UsageRow(attempt(1, 'passed', 2000,
                ['claude-x': new TokenUsage(200, 20, 3, 4), 'claude-y': new TokenUsage(50, 5, 5, 6)]))
        def rows = [row0, row1]
        def totals = fold(rows)

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

        and: 'the totals line is the snapshot: tokens over all rows, cache (3+18)=21, two rounds, unpriced'
        lines.last() == 'TOTAL: in=350 out=35 cache=21 rounds=2 priced=0 complete=false'
    }

    def "FR14: an empty history renders 'no rounds recorded' rather than an empty table"() {
        when:
        def output = renderer.render([], UsageSnapshot.EMPTY)

        then:
        output == 'no rounds recorded'
    }

    def "FR14: a row with no reported wall time renders a placeholder, not a crash"() {
        given:
        def row = new UsageRow(attempt(0, 'passed', null as Long, [:]))

        when:
        def output = renderer.render([row], fold([row]))

        then:
        noExceptionThrown()
        output.contains('-')
    }

    // FR17 of define-executor-contract: a row's tokens are summed over every participant, judge
    //     votes included, and its wall time is the executor's
    def "FR17: a row sums the executor's and the judge votes' tokens and shows the executor's wall time"() {
        given:
        def row = new UsageRow(attempt(0, 'passed', [
            Participants.transform(AttemptRecords.EXECUTOR,
            new ExecutorUsage(Duration.ofMillis(700), [], Unpriced.of(['m': new TokenUsage(1, 2, 3, 4)]))),
            Participants.verdict(Participants.JUDGE_EXECUTOR, 0, Unpriced.of(['j': new TokenUsage(10, 20, 30, 40)]))
        ], 'review'))

        when:
        def lines = renderer.render([row], fold([row])).readLines()

        then:
        lines[1].split(/\s+/) as List == [
            'review',
            '0',
            'passed',
            '11',
            '22',
            '77',
            '700'
        ]
        lines[2] == 'TOTAL: in=11 out=22 cache=77 rounds=1 priced=0 complete=false'
    }

    def "FR17: a row whose only entry is a judge vote shows no wall time"() {
        given:
        def row = new UsageRow(attempt(0, 'passed', [
            Participants.verdict(Participants.JUDGE_EXECUTOR, 0, ['j': new com.github.oinsio.gnomish.domain.engine.ModelUsage(
                        new TokenUsage(1, 1, 1, 1), new BigDecimal('0.5'))])
        ], 'review'))

        expect:
        renderer.render([row], fold([row])).readLines()[1].split(/\s+/).last() == '-'
        renderer.render([row], fold([row])).readLines()[2].endsWith('priced=0.5 complete=true')
    }
}
