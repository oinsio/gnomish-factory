package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.UsageRow
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.ExecutorRole
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ModelUsage
import com.github.oinsio.gnomish.domain.engine.ParticipantUsage
import com.github.oinsio.gnomish.domain.engine.Participants
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.UsageFold
import com.github.oinsio.gnomish.domain.engine.UsageProvenance
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR20, G5, M6 of define-executor-contract (design D16): {@code gnomish usage --markdown} renders
 * the report the usage-report stage posts — elapsed time, a per-stage, a per-role and a per-model
 * table — every token and cost figure taken from the one usage fold, the cost a stated lower bound
 * when it is incomplete. Built from canonical in-memory rows: a stage that needed two rounds, the
 * second judged by two votes, one of them unpriced.
 */
class UsageMarkdownRendererSpec extends Specification {

    static final Instant START = Instant.parse('2026-10-01T09:00:00Z')

    def renderer = new UsageMarkdownRenderer()

    private static AttemptRecord round(String stage, int n, int sequence, List<ParticipantUsage> usage,
            List<CheckResult> checks = []) {
        AttemptRecords.identified(AttemptRecords.TASK_ID, stage, RoundToken.of(String.format('%040x', sequence + 1)), n,
                AttemptRecord.Result.PASSED, START.plusSeconds(sequence * 600), checks, usage, [], Stop.none())
    }

    private static ParticipantUsage gnome(long wallSeconds, Map<String, ModelUsage> byModel) {
        Participants.transform(AttemptRecords.EXECUTOR, new ExecutorUsage(Duration.ofSeconds(wallSeconds), [], byModel))
    }

    private static Map<String, ModelUsage> priced(String model, TokenUsage tokens, String cost) {
        [(model): new ModelUsage(tokens, cost == null ? null : new BigDecimal(cost))]
    }

    private static CheckResult check(long seconds) {
        new CheckResult(new CheckRef(0, UntrustedText.manifest('tests')), new Verdict.Pass(null, []), Duration.ofSeconds(seconds))
    }

    /** implement: round 0 failed, round 1 passed under two judge votes (one unpriced); review: one round. */
    private static List<UsageRow> canonicalRows() {
        [
            round('implement', 0, 0, [
                gnome(60, priced('opus', new TokenUsage(100, 10, 1000, 5000), '0.40'))
            ], [check(30)]),
            round('implement', 1, 1, [
                gnome(120, priced('opus', new TokenUsage(200, 20, 2000, 6000), '0.60')),
                Participants.verdict(Participants.JUDGE_EXECUTOR, 0, priced('haiku', new TokenUsage(50, 5, 0, 100), '0.05')),
                Participants.verdict(Participants.JUDGE_EXECUTOR, 1, priced('haiku', new TokenUsage(40, 4, 0, 90), null))
            ], [check(45), check(15)]),
            round('review', 0, 2, [
                gnome(30, priced('sonnet', new TokenUsage(10, 1, 0, 0), '0.01'))
            ])
        ].collect { new UsageRow(it) }
    }

    /** The integer cells of one column of the table under {@code heading}, the total row left out. */
    private static List<Long> column(String report, String heading, int index) {
        def lines = report.readLines()
        def from = lines.indexOf(heading) + 4
        lines.subList(from, lines.size()).takeWhile { it.startsWith('|') }
        .findAll { !it.startsWith('| **Total**') }
        .collect {
            it.replaceAll(/ \|$/, '').split(/ \| /)[index].trim() as long
        }
    }

    // Scenario: cli/task-inspection / Markdown usage carries the stage, role and model tables
    def "FR20, G5: the per-stage sum equals the per-role sum equals the per-model sum equals the snapshot"() {
        given:
        def rows = canonicalRows()
        def snapshot = UsageFold.foldAll(rows*.attempt())
        def expected = UsageFold.count(UsageFold.total(snapshot))

        when:
        def report = renderer.render(rows, snapshot, START.plusSeconds(3 * 3600 + 120))

        then: 'the elapsed time runs from the first round, and the agent and check times are summed per round'
        report.startsWith(UsageMarkdownRenderer.HEADING + '\n')
        report.contains('Elapsed from the first round: 3 h 2 min, time parked for a human included; ' +
                'agent time 3 min 30 s, checks time 1 min 30 s')

        and: 'one stage row per stage in run order, the multi-round stage with both rounds and both votes'
        report.contains('| implement | 2 | 3 min 0 s | 1 min 30 s | 14619 | at least $1.05; cost incomplete: 134 tokens unpriced |')
        report.contains('| review | 1 | 30 s | 0 s | 11 | $0.0100 |')
        report.indexOf('| implement |') <report.indexOf('| review |')
        report.contains('| **Total** | 3 | 3 min 30 s | 1 min 30 s | ' + expected + ' | at least $1.06; cost incomplete: 134 tokens unpriced |')

        and: 'the three tables add up to the one snapshot'
        expected == 14630
        column(report, '### Time, tokens and cost per stage', 4).sum() == expected
        column(report, '### Tokens per role', 5).sum() == expected
        column(report, '### Tokens per model', 5).sum() == expected

        and: 'roles by record name with their render labels, models by id'
        report.contains('| transform (gnome) | 310 | 31 | 3000 | 11000 | 14341 |')
        report.contains('| verdict (judge) | 90 | 9 | 0 | 190 | 289 |')
        report.contains('| haiku | 90 | 9 | 0 | 190 | 289 |')
        report.contains('Source: 3 rounds recorded on the task branch')
    }

    // Scenario: cli/task-inspection / Markdown states the lower bound
    def "FR20: one judge vote with no cost makes every cost it touches 'at least' the priced sum, naming the unpriced tokens"() {
        given:
        def rows = [
            round('review', 0, 0, [
                gnome(10, priced('opus', new TokenUsage(1, 1, 0, 0), '2.5')),
                Participants.verdict(Participants.JUDGE_EXECUTOR, 0, priced('haiku', new TokenUsage(7, 3, 0, 0), null))
            ])
        ].collect { new UsageRow(it) }

        when:
        def report = renderer.render(rows, UsageFold.foldAll(rows*.attempt()), START)

        then:
        report.contains('; cost at least $2.50; cost incomplete: 10 tokens unpriced.')
        report.contains('| review | 1 | 10 s | 0 s | 12 | at least $2.50; cost incomplete: 10 tokens unpriced |')
    }

    def "FR20: a wall time is shown in its two largest units: #seconds s -> #shown"() {
        expect:
        UsageMarkdownCells.duration(Duration.ofSeconds(seconds)) == shown

        where:
        seconds || shown
        0 || '0 s'
        59 || '59 s'
        60 || '1 min 0 s'
        3599 || '59 min 59 s'
        3600 || '1 h 0 min'
        7380 || '2 h 3 min'
    }

    // FR20, NFR-S of type-untrusted-text: a name from the task branch stays inside its cell
    def "FR20: a stage name from the task branch leaves through the comment plane's inline exit, its pipe escaped"() {
        expect:
        UsageMarkdownCells.name('a|b @team #12\nnext') == 'a\\|b @​team #​12 next'
    }

    def "FR20: a role is shown as #label"() {
        given:
        def entry = new ParticipantUsage(role, 'x', role == ExecutorRole.VERDICT ? 0 : null, UsageProvenance.REPORTED,
                null, [], priced('m', new TokenUsage(1, 0, 0, 0), '0.1'))
        def rows = [
            new UsageRow(round('s', 0, 0, [entry]))
        ]

        expect:
        renderer.render(rows, UsageFold.foldAll(rows*.attempt()), START).contains('| ' + label + ' | 1 | 0 | 0 | 0 | 1 |')

        where:
        role || label
        ExecutorRole.TRANSFORM || 'transform (gnome)'
        ExecutorRole.VERDICT || 'verdict (judge)'
        ExecutorRole.DECISION || 'decision (arbiter)'
        ExecutorRole.EFFECT || 'effect'
    }

    def "FR20: a clock behind the first round's writer shows no negative elapsed time"() {
        given:
        def rows = [
            new UsageRow(round('s', 0, 0, [
                gnome(5, priced('m', new TokenUsage(1, 0, 0, 0), '0.1'))
            ]))
        ]

        expect:
        renderer.render(rows, UsageFold.foldAll(rows*.attempt()), START.minusSeconds(90))
                .contains('Elapsed from the first round: 0 s,')
    }

    def "FR20: no rounds renders the heading and a sentence, no empty tables"() {
        expect:
        renderer.render([], UsageSnapshot.EMPTY, START) ==
        UsageMarkdownRenderer.HEADING + '\n\nNo rounds are recorded on the task branch.'
    }
}
