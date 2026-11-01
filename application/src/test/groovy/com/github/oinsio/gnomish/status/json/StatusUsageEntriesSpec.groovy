package com.github.oinsio.gnomish.status.json

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorRole
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.ModelUsage
import com.github.oinsio.gnomish.domain.engine.ParticipantUsage
import com.github.oinsio.gnomish.domain.engine.Participants
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.ToolUsage
import com.github.oinsio.gnomish.domain.engine.UsageProvenance
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.domain.engine.fake.Unpriced
import com.github.oinsio.gnomish.executorcontract.usagewire.RecordedProvenance
import com.github.oinsio.gnomish.status.StatusReport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR15, FR16 of define-executor-contract: an attempt's {@code usage} in the status report lists one
 * participant entry per executor invocation — the executor, then each judge vote with its index —
 * in the one usage wire vocabulary, with no role-named slot (design D14).
 */
class StatusUsageEntriesSpec extends Specification {

    def mapper = new StatusReportJsonMapper()

    // FR9, D12 of add-agent-executor; FR15 of define-executor-contract: each judge vote is its own
    //     verdict entry with its vote index and its own model map, after the executor's entry
    // Scenario: observability/status-report / Judge votes are participants
    // Scenario: observability/status-report / A usage object carries wall time, tools and tokens by model
    def "an attempt's usage lists the executor and each judge vote as participant entries"() {
        given: 'an attempt whose executor reported a priced model and whose judge cast two votes'
        def context = new TaskContext("task-1", UntrustedText.tracker("Title"), UntrustedText.tracker("Body"), [])
        def executed = new ParticipantUsage(ExecutorRole.TRANSFORM, 'acme', null, UsageProvenance.REPORTED,
                Duration.ofMillis(900), [
                    new ToolUsage('Edit', 2, Duration.ofMillis(40))
                ],
                ['model-b': new ModelUsage(new TokenUsage(1, 2, 3, 4), new BigDecimal('0.25')),
                    'model-a': new ModelUsage(new TokenUsage(5, 6, 7, 8), null)])
        def usage = [
            executed,
            Participants.verdict('agent-cli', 0, Unpriced.of(['model-a': new TokenUsage(100, 20, 0, 0)])),
            Participants.verdict('agent-cli', 1, Unpriced.of(['model-a': new TokenUsage(150, 30, 0, 0)]))
        ]
        def attempt = AttemptRecords.of(
                0, AttemptRecord.Result.PASSED, Instant.parse("2026-07-17T09:00:00Z"), [], usage, [], Stop.none())
        def state = new TaskState(new Position.AtStage("implement"), 0, [attempt], UsageSnapshot.EMPTY, ExecutorVersions.none())

        when:
        def json = new ObjectMapper().readValue(mapper.serialize(StatusReport.build(context, state, null, null)), Map)
        def entries = json.currentStage.attempts[0].usage

        then: 'the executor entry carries its role, name, provenance, wall time, tools and models, no vote'
        entries[0] == [role: 'transform', executor: 'acme', provenance: 'reported', wallMillis: 900,
            byTool: [
                [name: 'Edit', calls: 2, totalMillis: 40]
            ],
            byModel: ['model-a': [input: 5, output: 6, cacheCreation: 7, cacheRead: 8],
                'model-b': [input: 1, output: 2, cacheCreation: 3, cacheRead: 4, costUsd: 0.25d]]]

        and: 'the models are written in model-id order, and an unpriced model carries no costUsd key'
        entries[0].byModel.keySet() as List == ['model-a', 'model-b']

        and: 'each vote is a verdict entry with vote 0 and 1, each with its own byModel'
        entries[1..2]*.role == ['verdict', 'verdict']
        entries[1..2]*.vote == [0, 1]
        entries[1].byModel == ['model-a': [input: 100, output: 20, cacheCreation: 0, cacheRead: 0]]
        entries[2].byModel == ['model-a': [input: 150, output: 30, cacheCreation: 0, cacheRead: 0]]

        and: 'no judgeUsage or role-named usage field exists'
        !json.currentStage.attempts[0].containsKey('judgeUsage')
    }

    // FR9, NFR-C1, D4, D12 of add-agent-executor; FR15 of define-executor-contract: a cast vote that
    //     reported no tokens is an absent entry with an empty model map, never a fabricated zero entry
    def "a judge vote that reported no token is an absent verdict entry with an empty byModel"() {
        given:
        def context = new TaskContext("task-1", UntrustedText.tracker("Title"), UntrustedText.tracker("Body"), [])
        def attempt = AttemptRecords.of(
                0, AttemptRecord.Result.PASSED, Instant.parse("2026-07-17T09:00:00Z"), [],
                [
                    Participants.transform('acme', ExecutorUsage.none()),
                    Participants.verdict('agent-cli', 0, [:])
                ], [],
                Stop.none())
        def state = new TaskState(new Position.AtStage("implement"), 0, [attempt], UsageSnapshot.EMPTY, ExecutorVersions.none())

        when:
        def vote = mapper.toDto(StatusReport.build(context, state, null, null)).currentStage().attempts()[0].usage()[1]

        then:
        vote.provenance() == RecordedProvenance.ABSENT
        vote.vote() == 0
        vote.byModel() == [:]
        vote.wallMillis() == null
    }
}
