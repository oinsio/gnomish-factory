package com.github.oinsio.gnomish.usage.json

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.app.port.git.UsageRow
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.Participants
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.ToolUsage
import com.github.oinsio.gnomish.domain.engine.UsageFold
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.domain.engine.fake.Unpriced
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR14, NFR-C1 of add-git-workflow: {@code gnomish usage --json} renders its own {@code
 * "version": 1} mini-contract (design D5, separate from status-report v1) at full granularity —
 * every participant entry of every round with its per-model tokens and per-tool aggregates, each
 * judge vote its own entry — never summed (FR15, FR16 of define-executor-contract).
 */
class UsageReportJsonMapperSpec extends Specification {

    def mapper = new UsageReportJsonMapper()
    def reader = new ObjectMapper()

    // Scenario: cli/task-inspection / JSON usage carries full granularity under its own version
    def "FR14, NFR-C1: the envelope carries its own version:1 and full per-model/per-vote granularity"() {
        given:
        def checks = [
            new CheckResult(new CheckRef(0, UntrustedText.manifest('tests')), new Verdict.Fail([
                new Finding('boom', 'Foo.java:10', null)
            ]), Duration.ofMillis(500))
        ]
        def executorUsage = new ExecutorUsage(Duration.ofMillis(1500),
                [
                    new ToolUsage('bash', 3, Duration.ofMillis(900))
                ],
                Unpriced.of(['claude-x': new TokenUsage(100, 10, 1, 2), 'claude-y': new TokenUsage(50, 5, 0, 0)]))
        def usage = [
            Participants.transform('agent-cli', executorUsage),
            Participants.verdict('agent-cli', 0, Unpriced.of(['claude-z': new TokenUsage(20, 2, 0, 0)]))
        ]
        def attempt = AttemptRecords.of(0, AttemptRecord.Result.QUALITY_FAILURE,
                Instant.parse('2026-07-18T09:00:00Z'), checks, usage, [], Stop.none())
        def row = new UsageRow(attempt)
        def totals = UsageFold.foldAll([attempt])

        when:
        def json = mapper.serialize('PROJ-1', [row], totals)
        def tree = reader.readTree(json)

        then: 'own version:1 envelope, taskId, one row'
        tree.get('version').asInt() == 1
        tree.get('taskId').asText() == 'PROJ-1'
        tree.get('rows').size() == 1

        and: 'the row keeps per-model granularity, not summed'
        def rowNode = tree.get('rows')[0]
        rowNode.get('stage').asText() == AttemptRecords.STAGE
        rowNode.get('round').asInt() == 0
        rowNode.get('result').asText() == 'qualityFailure'
        def executed = rowNode.get('usage')[0]
        executed.get('role').asText() == 'transform'
        executed.get('byModel').get('claude-x').get('input').asLong() == 100L
        executed.get('byModel').get('claude-y').get('input').asLong() == 50L
        executed.get('byTool')[0].get('name').asText() == 'bash'

        and: 'the check and its finding round-trip'
        rowNode.get('checks')[0].get('ref').asText() == 'tests'
        rowNode.get('checks')[0].get('findings')[0].get('message').asText() == 'boom'

        and: 'the judge vote is its own verdict entry, not folded into totals'
        rowNode.get('usage').size() == 2
        rowNode.get('usage')[1].get('role').asText() == 'verdict'
        rowNode.get('usage')[1].get('vote').asInt() == 0
        rowNode.get('usage')[1].get('byModel').get('claude-z').get('input').asLong() == 20L
        !rowNode.has('executorUsage')
        !rowNode.has('judgeUsage')

        and: 'totals are the snapshot: tokens per role and model, the cost a lower bound, the position'
        tree.get('totals').get('byRole').get('transform').get('claude-x').get('input').asLong() == 100L
        tree.get('totals').get('byRole').get('verdict').get('claude-z').get('input').asLong() == 20L
        !tree.get('totals').get('byRole').get('transform').get('claude-x').has('costUsd')
        !tree.get('totals').get('cost').get('complete').asBoolean()
        tree.get('totals').get('rounds').asInt() == 1
        tree.get('totals').get('through').asText() == AttemptRecords.TOKEN.commit()
    }

    // FR7 of migrate-verify-to-executor-contract (D5 as amended): the usage report records a pass's findings as a failure's
    def "FR7: a passing check's findings are carried in order"() {
        given:
        def pass = new Verdict.Pass(null, [
            new Finding('w1', 'A.java:1', null),
            new Finding('w2', null, 'detail')
        ])
        def attempt = AttemptRecords.of(0, AttemptRecord.Result.PASSED, Instant.parse('2026-07-18T09:00:00Z'),
                [
                    new CheckResult(new CheckRef(0, UntrustedText.manifest('lint')), pass, Duration.ofMillis(5))
                ],
                ExecutorUsage.none(), [], Stop.none())

        when:
        def tree = reader.readTree(mapper.serialize('PROJ-1', [new UsageRow(attempt)], UsageFold.foldAll([attempt])))

        then:
        def check = tree.get('rows')[0].get('checks')[0]
        check.get('verdict').asText() == 'pass'
        check.get('findings')*.get('message')*.asText() == ['w1', 'w2']
        check.get('findings')[0].get('location').asText() == 'A.java:1'
        check.get('findings')[1].get('details').asText() == 'detail'
    }

    def "FR14: an empty history serializes an empty rows array and the empty snapshot"() {
        when:
        def json = mapper.serialize('PROJ-2', [], UsageSnapshot.EMPTY)
        def tree = reader.readTree(json)

        then:
        tree.get('version').asInt() == 1
        tree.get('rows').size() == 0
        tree.get('totals').get('rounds').asInt() == 0
        !tree.get('totals').has('through')
    }
}
