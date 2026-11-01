package com.github.oinsio.gnomish.adapter.git.state

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorRole
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.ModelUsage
import com.github.oinsio.gnomish.domain.engine.ParticipantUsage
import com.github.oinsio.gnomish.domain.engine.Participants
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.UsageProvenance
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.domain.engine.fake.Unpriced
import com.github.oinsio.gnomish.executorcontract.usagewire.ParticipantUsageDto
import com.github.oinsio.gnomish.status.StatusReportReferenceFixture
import com.github.oinsio.gnomish.status.json.StatusReportJsonMapper
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR15, FR16 of define-executor-contract: {@code state.json} records a round's usage as one list of
 * participant entries in the one usage wire vocabulary — the executor and each judge vote, no
 * role-named slot, an unpriced model never written as a zero cost — the same entries the status
 * report renders (design D10, D14).
 */
class StateUsageWireSpec extends Specification {

    def startedAt = Instant.parse("2026-07-18T09:00:00Z")

    // FR15 of define-executor-contract: the executor and every judge vote ride one usage list, and
    //     no role-named slot is written
    // Scenario: git/git-task-persistence / No per-kind usage field is written
    def "FR15: a round with an executor and judge votes is written as one usage list, no per-kind field"() {
        given:
        def usage = AttemptRecords.executed(new ExecutorUsage(Duration.ofSeconds(2), [], Unpriced.of(["m": new TokenUsage(1, 2, 3, 4)]))) +
        [
            Participants.verdict('agent-cli', 0, Unpriced.of(["m": new TokenUsage(5, 6, 0, 0)])),
            Participants.verdict('agent-cli', 1, [:])
        ]
        def record = AttemptRecords.of(0, AttemptRecord.Result.PASSED, startedAt, [], usage, [], Stop.none())
        def state = new TaskState(new Position.AtStage("implement"), 0, [record], UsageSnapshot.EMPTY, ExecutorVersions.none())

        when:
        def json = TaskStateJson.mapper().writeValueAsString(StateJsonMapper.toDto(state))
        def attempt = TaskStateJson.mapper().readTree(json).get("attempts").get(0)

        then: 'one list holds every participant, in order'
        attempt.get("usage").collect {
            it.get("role").asText()
        } == [
            "transform",
            "verdict",
            "verdict"
        ]
        attempt.get("usage").get(1).get("vote").asInt() == 0
        attempt.get("usage").get(2).get("provenance").asText() == "absent"

        and: 'no role-named slot exists'
        !attempt.has("executorUsage")
        !attempt.has("judgeUsage")

        and: 'the record reads back with its participants'
        StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(json))).attempts()[0].usage() == usage
    }

    // FR16 of define-executor-contract: state.json spells a participant entry through the one usage
    //     wire vocabulary the status report spells it through, never through a status-report DTO
    // Scenario: git/git-task-persistence / State files and status share one usage vocabulary
    def "FR16: the state file's usage entries are the shared vocabulary's DTOs, equal to the status report's"() {
        given:
        def record = StatusReportReferenceFixture.referenceTaskState().attempts()[0]
        def state = new TaskState(new Position.AtStage("implement"), 1, [record], UsageSnapshot.EMPTY, ExecutorVersions.none())

        when:
        def stateEntries = StateJsonMapper.toDto(state).attempts()[0].usage()
        def statusEntries = new StatusReportJsonMapper().toDto(StatusReportReferenceFixture.referenceReport())
                .currentStage().attempts()[0].usage()

        then:
        stateEntries.every { it instanceof ParticipantUsageDto }
        stateEntries == statusEntries
        StateAttemptDto.recordComponents.find {
            it.name == "usage"
        }.genericType.typeName ==
        "java.util.List<${ParticipantUsageDto.name}>"
        !StateAttemptDto.recordComponents*.type*.package*.name.contains("com.github.oinsio.gnomish.status.json")
    }

    // FR15 of define-executor-contract: an unpriced model stays unpriced through the state file
    // Scenario: pipeline/stage-engine / A missing cost is absent, never zero
    def "FR15: a recorded model without a cost carries no cost, never a zero cost, and a priced one keeps it"() {
        given:
        def entry = new com.github.oinsio.gnomish.domain.engine.ParticipantUsage(
                com.github.oinsio.gnomish.domain.engine.ExecutorRole.TRANSFORM, 'agent-cli', null,
                com.github.oinsio.gnomish.domain.engine.UsageProvenance.REPORTED, null, [],
                ["unpriced": new ModelUsage(new TokenUsage(1, 1, 0, 0), null),
                    "priced": new ModelUsage(new TokenUsage(1, 1, 0, 0), new BigDecimal("0.0042"))])
        def record = AttemptRecords.of(0, AttemptRecord.Result.PASSED, startedAt, [], [entry], [], Stop.none())
        def state = new TaskState(new Position.AtStage("implement"), 0, [record], UsageSnapshot.EMPTY, ExecutorVersions.none())

        when:
        def json = TaskStateJson.mapper().writeValueAsString(StateJsonMapper.toDto(state))
        def model = TaskStateJson.mapper().readTree(json).get("attempts").get(0).get("usage").get(0).get("byModel")
        def read = StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(json)))
                .attempts()[0].usage()[0].byModel()

        then:
        !model.get("unpriced").has("costUsd")
        model.get("priced").get("costUsd").decimalValue() == new BigDecimal("0.0042")
        read["unpriced"].costUsd() == null
        read["priced"].costUsd() == new BigDecimal("0.0042")
    }

    // FR15: an attempt document without the usage field reads as an empty list, never null
    def "an attempt without a usage field reads as an empty participant list"() {
        given:
        def json = """
        {"version": 1, "position": {"type": "atStage", "stage": "implement"}, "attemptsUsed": 0,
         "attempts": [{"taskId": "T-1", "stage": "implement", "round": 0, "result": "passed",
                       "startedAt": "2026-07-18T09:00:00Z", "checks": []}],
         "totals": {"byRole": {}, "cost": {"priced": 0, "unpricedTokens": {}, "complete": true}, "rounds": 0}}
        """

        when:
        def dto = StateJsonMapper.readDto(UntrustedText.branchDocument(json))

        then:
        dto.attempts()[0].usage() == []

        when:
        StateJsonMapper.fromDto(dto)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message == 'state.json attempt 0 carries no usage entry'
    }
}
