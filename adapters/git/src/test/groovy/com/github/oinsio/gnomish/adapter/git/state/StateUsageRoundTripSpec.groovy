package com.github.oinsio.gnomish.adapter.git.state

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.ModelUsage
import com.github.oinsio.gnomish.domain.engine.Participants
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.ToolUsage
import com.github.oinsio.gnomish.domain.engine.UsageFold
import com.github.oinsio.gnomish.domain.engine.UsageProvenance
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR15, FR17 of define-executor-contract: a round's participant entries and the task's usage
 * snapshot survive {@code state.json} as written — serialized to the document text and read back
 * through the reader every pickup uses — with a missing cost kept absent, an {@code ABSENT} entry's
 * model map kept empty, and the snapshot's position ({@code rounds}, {@code through}) and incomplete
 * cost kept as folded.
 */
class StateUsageRoundTripSpec extends Specification {

    static final Instant STARTED = Instant.parse('2026-07-18T09:00:00Z')

    /** A round with a priced transform entry, an unpriced verdict vote and an {@code ABSENT} vote. */
    static AttemptRecord round() {
        def executor = Participants.transform('acme', new ExecutorUsage(Duration.ofMillis(1200),
                [
                    new ToolUsage('lint', 2, Duration.ofMillis(300))
                ],
                ['acme-model-1': new ModelUsage(new TokenUsage(120, 45, 0, 10), new BigDecimal('0.0031'))]))
        def unpriced = Participants.verdict('agent-cli', 0, ['claude-j': new ModelUsage(new TokenUsage(7, 3, 0, 0), null)])
        def absent = Participants.verdict('agent-cli', 1, [:])
        AttemptRecords.of(0, AttemptRecord.Result.PASSED, STARTED, [], [executor, unpriced, absent], [], Stop.none())
    }

    static TaskState written(AttemptRecord record, UsageSnapshot totals) {
        new TaskState(new Position.AtStage('review'), 0, [record], totals, ExecutorVersions.none())
    }

    /** {@code state} written to the document text and read back through the pickup's reader. */
    static TaskState readBack(TaskState state) {
        def json = TaskStateJson.mapper().writeValueAsString(StateJsonMapper.toDto(state))
        StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(json)))
    }

    // Scenario: git/git-task-persistence / Every participant entry round-trips
    // FR15: a priced, an unpriced and an ABSENT entry read back as written
    def "FR15: a round's transform entry with cost, verdict entry without cost and ABSENT entry read back as written"() {
        given:
        def record = round()

        when:
        def read = readBack(written(record, UsageSnapshot.EMPTY)).attempts()[0]

        then: 'the read record equals the written one'
        read == record

        and: 'the missing cost stays absent, and the ABSENT entry carries no model'
        read.usage()[1].byModel()['claude-j'].costUsd() == null
        read.usage()[2].provenance() == UsageProvenance.ABSENT
        read.usage()[2].byModel().isEmpty()
        read.usage()[0].byModel()['acme-model-1'].costUsd() == new BigDecimal('0.0031')
    }

    // Scenario: git/git-task-persistence / The snapshot round-trips with its position
    // FR17: the totals keep their rounds, their through token and their incomplete cost
    def "FR17: a snapshot carrying rounds, through and an incomplete cost reads back as written"() {
        given: 'the snapshot the fold builds over the round: one round, its token, a priced and an unpriced model'
        def record = round()
        def totals = UsageFold.fold(UsageSnapshot.EMPTY, record)

        expect: 'the snapshot holds every part the scenario names'
        totals.rounds() == 1
        totals.through() == AttemptRecords.TOKEN
        !totals.cost().complete()
        totals.cost().priced() == new BigDecimal('0.0031')

        when:
        def read = readBack(written(record, totals)).totals()

        then:
        read == totals
    }
}
