package com.github.oinsio.gnomish.usage.json

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.git.UsageRow
import com.github.oinsio.gnomish.adapter.git.UsageTotals
import com.github.oinsio.gnomish.adapter.git.state.StateAttemptDto
import com.github.oinsio.gnomish.adapter.git.state.StateCheckDto
import com.github.oinsio.gnomish.adapter.git.state.StateFindingDto
import com.github.oinsio.gnomish.adapter.git.state.StateJudgeUsageDto
import com.github.oinsio.gnomish.adapter.git.state.StateTokenUsageDto
import com.github.oinsio.gnomish.adapter.git.state.StateUsageDto
import spock.lang.Specification

/**
 * FR14, NFR-C1 of add-git-workflow: {@code gnomish usage --json} renders its own {@code
 * "version": 1} mini-contract (design D5, separate from status-report v1) at full granularity —
 * every model's token breakdown, per-vote judge usage, per-tool aggregates — never summed.
 */
class UsageReportJsonMapperSpec extends Specification {

    def mapper = new UsageReportJsonMapper()
    def reader = new ObjectMapper()

    def "FR14, NFR-C1: the envelope carries its own version:1 and full per-model/per-vote granularity"() {
        given:
        def checks = [
            new StateCheckDto('tests', 'fail', [
                new StateFindingDto('boom', 'Foo.java:10', null)
            ], 500, null, null)
        ]
        def executorUsage = new StateUsageDto(1500L,
                ['claude-x': new StateTokenUsageDto(100, 10, 1, 2), 'claude-y': new StateTokenUsageDto(50, 5, 0, 0)],
                [
                    new com.github.oinsio.gnomish.adapter.git.state.StateByToolDto('bash', 3, 900)
                ])
        def judgeUsage = new StateJudgeUsageDto([
            new StateJudgeUsageDto.Vote(['claude-z': new StateTokenUsageDto(20, 2, 0, 0)])
        ])
        def attempt = new StateAttemptDto(0, 'qualityFailure', '2026-07-18T09:00:00Z', checks, executorUsage, judgeUsage)
        def row = new UsageRow('implement', attempt)
        def totals = UsageTotals.of([row])

        when:
        def json = mapper.serialize('PROJ-1', [row], totals)
        def tree = reader.readTree(json)

        then: 'own version:1 envelope, taskId, one row'
        tree.get('version').asInt() == 1
        tree.get('taskId').asText() == 'PROJ-1'
        tree.get('rows').size() == 1

        and: 'the row keeps per-model granularity, not summed'
        def rowNode = tree.get('rows')[0]
        rowNode.get('stage').asText() == 'implement'
        rowNode.get('round').asInt() == 0
        rowNode.get('result').asText() == 'qualityFailure'
        rowNode.get('executorUsage').get('tokensByModel').get('claude-x').get('input').asLong() == 100L
        rowNode.get('executorUsage').get('tokensByModel').get('claude-y').get('input').asLong() == 50L
        rowNode.get('executorUsage').get('byTool')[0].get('name').asText() == 'bash'

        and: 'the check and its finding round-trip'
        rowNode.get('checks')[0].get('ref').asText() == 'tests'
        rowNode.get('checks')[0].get('findings')[0].get('message').asText() == 'boom'

        and: 'per-vote judge usage is preserved, not folded into totals'
        rowNode.get('judgeUsage').get('perVote').size() == 1
        rowNode.get('judgeUsage').get('perVote')[0].get('tokensByModel').get('claude-z').get('input').asLong() == 20L

        and: 'totals sum wall time and per-model tokens across rows, no byTool'
        tree.get('totals').get('wallMillis').asLong() == 1500L
        tree.get('totals').get('tokensByModel').get('claude-x').get('input').asLong() == 100L
        tree.get('totals').get('byTool').size() == 0
    }

    def "FR14: an empty history serializes an empty rows array and null totals wall time"() {
        when:
        def json = mapper.serialize('PROJ-2', [], UsageTotals.empty())
        def tree = reader.readTree(json)

        then:
        tree.get('version').asInt() == 1
        tree.get('rows').size() == 0
        tree.get('totals').get('wallMillis').isNull()
    }
}
