package com.github.oinsio.gnomish.adapter.git.state

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.time.Instant
import spock.lang.Specification

/**
 * Verifies {@link TaskJsonMapper} against the {@code task.json} v1 contract:
 * mapping FROM domain objects (TaskContext, Decision, TaskOutcome, EscalationReport)
 * through the DTO tree and back, covering every sealed-type branch (all 4
 * TaskOutcome kinds, all 5 EscalationReport kinds), plus null outcome/lastEscalation.
 *
 * FR3, FR4: task.json v1 contract, DTOs and mappers.
 */
class TaskJsonMapperSpec extends Specification {

    def someContext = new TaskContext(
    "task-1", "Fix flaky test", "body text",
    [
        new Decision("patch in place", "plan", "operator", Instant.parse("2026-07-16T14:21:30Z"))
    ])
    def createdAt = Instant.parse("2026-07-18T09:00:00Z")
    def baseCommit = "abc123"

    def "toDto maps taskId, title, body, createdAt, baseCommit, version=1"() {
        when:
        def dto = TaskJsonMapper.toDto(someContext, baseCommit, createdAt, null, null)

        then:
        dto.version() == 1
        dto.taskId() == "task-1"
        dto.title() == "Fix flaky test"
        dto.body() == "body text"
        dto.createdAt() == "2026-07-18T09:00:00Z"
        dto.baseCommit() == "abc123"
    }

    def "toDto maps decisions in order with author, stage, at"() {
        when:
        def dto = TaskJsonMapper.toDto(someContext, baseCommit, createdAt, null, null)

        then:
        dto.decisions() == [
            new TaskDecisionDto("patch in place", "operator", "plan", "2026-07-16T14:21:30Z")
        ]
    }

    def "toDto maps a decision with null author/stage/at"() {
        given:
        def context = new TaskContext("task-1", "t", "b", [
            new Decision("just a note", null, null, null)
        ])

        when:
        def dto = TaskJsonMapper.toDto(context, baseCommit, createdAt, null, null)

        then:
        dto.decisions() == [
            new TaskDecisionDto("just a note", null, null, null)
        ]
    }

    def "toDto renders outcome and lastEscalation as null when both absent"() {
        when:
        def dto = TaskJsonMapper.toDto(someContext, baseCommit, createdAt, null, null)

        then:
        dto.outcome() == null
        dto.lastEscalation() == null
    }

    def "toDto renders outcome completed"() {
        given:
        def outcome = new TaskOutcome.Completed(someState())

        when:
        def dto = TaskJsonMapper.toDto(someContext, baseCommit, createdAt, outcome, null)

        then:
        dto.outcome() == new TaskOutcomeDto.Completed("completed")
    }

    def "toDto renders outcome paused with passedStage"() {
        given:
        def outcome = new TaskOutcome.Paused(someState(), "implement")

        when:
        def dto = TaskJsonMapper.toDto(someContext, baseCommit, createdAt, outcome, null)

        then:
        dto.outcome() == new TaskOutcomeDto.Paused("paused", "implement")
    }

    def "toDto renders outcome escalated with nested report"() {
        given:
        def outcome = new TaskOutcome.Escalated(someState(), new EscalationReport.AttemptsExhausted(3))

        when:
        def dto = TaskJsonMapper.toDto(someContext, baseCommit, createdAt, outcome, null)

        then:
        dto.outcome() == new TaskOutcomeDto.Escalated(
                "escalated", new EscalationReportDto.AttemptsExhausted("attemptsExhausted", 3))
    }

    def "toDto renders outcome aborted with failedAt and cause"() {
        given:
        def failedAt = new AttemptKey("task-1", "implement", 2)
        def outcome = new TaskOutcome.Aborted(someState(), failedAt, "disk full")

        when:
        def dto = TaskJsonMapper.toDto(someContext, baseCommit, createdAt, outcome, null)

        then:
        dto.outcome() == new TaskOutcomeDto.Aborted("aborted", failedAt.toString(), "disk full")
    }

    def "toDto renders lastEscalation independently of outcome"() {
        given:
        def lastEscalation = new EscalationReport.DecisionNeeded("Refactor or patch?", ["refactor", "patch"])

        when:
        def dto = TaskJsonMapper.toDto(someContext, baseCommit, createdAt, null, lastEscalation)

        then:
        dto.outcome() == null
        dto.lastEscalation() ==
                new EscalationReportDto.DecisionNeeded("decisionNeeded", "Refactor or patch?", ["refactor", "patch"])
    }

    def "toDto maps every EscalationReport kind"() {
        expect:
        TaskJsonMapper.toDto(someContext, baseCommit, createdAt, null, report).lastEscalation() == expected

        where:
        report                                                                          | expected
        new EscalationReport.AttemptsExhausted(3)                                       | new EscalationReportDto.AttemptsExhausted("attemptsExhausted", 3)
        new EscalationReport.DecisionNeeded("Q?", ["a", "b"])                           | new EscalationReportDto.DecisionNeeded("decisionNeeded", "Q?", ["a", "b"])
        new EscalationReport.CannotVerify(new CheckRef(0, "command:./gradlew test"), "timeout", "detail") | new EscalationReportDto.CannotVerify("cannotVerify", "command:./gradlew test", "timeout", "detail")
        new EscalationReport.PipelineMismatch("removed-stage")                          | new EscalationReportDto.PipelineMismatch("pipelineMismatch", "removed-stage")
        new EscalationReport.CannotExecute("adapter crashed")                           | new EscalationReportDto.CannotExecute("cannotExecute", "adapter crashed")
    }

    def "round-trip: serialize then deserialize a full TaskJsonDto tree is equal"() {
        given:
        def mapper = TaskStateJson.mapper()
        def dto = new TaskJsonDto(
                1,
                "task-1",
                "Title",
                "Body",
                "2026-07-18T09:00:00Z",
                "abc123",
                [
                    new TaskDecisionDto("patch in place", "operator", "plan", "2026-07-16T14:21:30Z")
                ],
                new TaskOutcomeDto.Escalated(
                "escalated",
                new EscalationReportDto.CannotVerify("cannotVerify", "external:ci", "timeout", "poll exceeded")),
                new EscalationReportDto.DecisionNeeded("decisionNeeded", "Refactor or patch?", ["refactor", "patch"]))

        when:
        def json = mapper.writeValueAsString(dto)
        def roundTripped = mapper.readValue(json, TaskJsonDto)

        then:
        roundTripped == dto
    }

    def "round-trip: null outcome and null lastEscalation survive serialize/deserialize"() {
        given:
        def mapper = TaskStateJson.mapper()
        def dto = new TaskJsonDto(1, "task-1", "Title", "Body", "2026-07-18T09:00:00Z", "abc123", [], null, null)

        when:
        def json = mapper.writeValueAsString(dto)
        def roundTripped = mapper.readValue(json, TaskJsonDto)

        then:
        roundTripped == dto
        json.contains('"outcome":null')
        json.contains('"lastEscalation":null')
    }

    def "round-trip: every TaskOutcomeDto kind survives serialize/deserialize"() {
        given:
        def mapper = TaskStateJson.mapper()
        def dto = new TaskJsonDto(1, "task-1", "Title", "Body", "2026-07-18T09:00:00Z", "abc123", [], outcome, null)

        when:
        def json = mapper.writeValueAsString(dto)
        def roundTripped = mapper.readValue(json, TaskJsonDto)

        then:
        roundTripped.outcome() == outcome

        where:
        outcome << [
            new TaskOutcomeDto.Completed("completed"),
            new TaskOutcomeDto.Paused("paused", "implement"),
            new TaskOutcomeDto.Escalated("escalated", new EscalationReportDto.AttemptsExhausted("attemptsExhausted", 3)),
            new TaskOutcomeDto.Aborted("aborted", "AttemptKey[taskId=task-1, stage=implement, attempt=2]", "disk full")
        ]
    }

    def "round-trip: every EscalationReportDto kind survives serialize/deserialize as lastEscalation"() {
        given:
        def mapper = TaskStateJson.mapper()
        def dto = new TaskJsonDto(1, "task-1", "Title", "Body", "2026-07-18T09:00:00Z", "abc123", [], null, escalation)

        when:
        def json = mapper.writeValueAsString(dto)
        def roundTripped = mapper.readValue(json, TaskJsonDto)

        then:
        roundTripped.lastEscalation() == escalation

        where:
        escalation << [
            new EscalationReportDto.AttemptsExhausted("attemptsExhausted", 3),
            new EscalationReportDto.DecisionNeeded("decisionNeeded", "Q?", ["a", "b"]),
            new EscalationReportDto.CannotVerify("cannotVerify", "command:./gradlew test", "timeout", "detail"),
            new EscalationReportDto.PipelineMismatch("pipelineMismatch", "removed-stage"),
            new EscalationReportDto.CannotExecute("cannotExecute", "adapter crashed")
        ]
    }

    def "round-trip: unknown fields in the JSON are ignored on read"() {
        given:
        def mapper = TaskStateJson.mapper()
        def json = '''
        {
          "version": 1,
          "taskId": "task-1",
          "title": "Title",
          "body": "Body",
          "createdAt": "2026-07-18T09:00:00Z",
          "baseCommit": "abc123",
          "decisions": [],
          "outcome": null,
          "lastEscalation": null,
          "someFutureField": "ignored"
        }
        '''

        when:
        def dto = mapper.readValue(json, TaskJsonDto)

        then:
        dto.taskId() == "task-1"
        dto.version() == 1
    }

    def "readDto parses a version-1 document, tolerating unknown fields"() {
        given:
        def json = '''
        {
          "version": 1,
          "taskId": "task-1",
          "title": "Title",
          "body": "Body",
          "createdAt": "2026-07-18T09:00:00Z",
          "baseCommit": "abc123",
          "decisions": [],
          "outcome": null,
          "lastEscalation": null,
          "someFutureField": "ignored"
        }
        '''

        when:
        def dto = TaskJsonMapper.readDto(json)

        then:
        dto.version() == 1
        dto.taskId() == "task-1"
    }

    def "readDto refuses an unsupported version before attempting to bind the DTO shape"() {
        given:
        // shape is incompatible with TaskJsonDto (no taskId/title/body/etc.) to
        // prove the version gate runs before any record binding is attempted
        def json = '{"version": 2, "somethingElseEntirely": true}'

        when:
        TaskJsonMapper.readDto(json)

        then:
        def e = thrown(UnsupportedStateFileVersionException)
        e.message == "task.json: unsupported version 2 (supported: 1)"
        e.fileName() == "task.json"
        e.foundVersion() == 2
        e.supportedVersion() == 1
    }

    def "readDto refuses a document with no version field"() {
        given:
        def json = '{"taskId": "task-1"}'

        when:
        TaskJsonMapper.readDto(json)

        then:
        def e = thrown(UnsupportedStateFileVersionException)
        e.message == "task.json: missing (supported: 1)"
        e.foundVersion() == -1
    }

    def "fromDto maps taskId, title, body, decisions, baseCommit, createdAt back to domain"() {
        given:
        def dto = TaskJsonMapper.toDto(someContext, baseCommit, createdAt, null, null)

        when:
        def content = TaskJsonMapper.fromDto(dto)

        then:
        content.context() == someContext
        content.baseCommit() == baseCommit
        content.createdAt() == createdAt
        content.outcome() == null
        content.lastEscalation() == null
    }

    def "fromDto maps lastEscalation back to a domain EscalationReport"() {
        given:
        def lastEscalation = new EscalationReport.PipelineMismatch("removed-stage")
        def dto = TaskJsonMapper.toDto(someContext, baseCommit, createdAt, null, lastEscalation)

        when:
        def content = TaskJsonMapper.fromDto(dto)

        then:
        content.lastEscalation() == lastEscalation
    }

    def "fromDto keeps outcome at the DTO level for every TaskOutcome kind"() {
        given:
        def dto = TaskJsonMapper.toDto(someContext, baseCommit, createdAt, outcome, null)

        when:
        def content = TaskJsonMapper.fromDto(dto)

        then:
        content.outcome() == expectedDto

        where:
        outcome                                                                                    | expectedDto
        new TaskOutcome.Completed(someState())                                                      | new TaskOutcomeDto.Completed("completed")
        new TaskOutcome.Paused(someState(), "implement")                                            | new TaskOutcomeDto.Paused("paused", "implement")
        new TaskOutcome.Escalated(someState(), new EscalationReport.AttemptsExhausted(3))           | new TaskOutcomeDto.Escalated("escalated", new EscalationReportDto.AttemptsExhausted("attemptsExhausted", 3))
        new TaskOutcome.Aborted(someState(), new AttemptKey("task-1", "implement", 2), "disk full") | new TaskOutcomeDto.Aborted("aborted", new AttemptKey("task-1", "implement", 2).toString(), "disk full")
    }

    private static TaskState someState() {
        return new TaskState(new Position.AtStage("implement"), 0, [], ExecutorUsage.none())
    }
}
