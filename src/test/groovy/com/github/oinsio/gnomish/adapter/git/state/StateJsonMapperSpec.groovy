package com.github.oinsio.gnomish.adapter.git.state

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.ToolUsage
import com.github.oinsio.gnomish.domain.engine.Verdict
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * Verifies {@link StateJsonMapper} against the {@code state.json} v1 contract:
 * mapping FROM a domain {@link TaskState} through the DTO tree and back to an
 * equal {@code TaskState}, covering every sealed-type branch (all 4
 * AttemptRecord.Result values, all 3 Verdict kinds, both Position variants),
 * plus empty/populated tokensByModel/tools/checks and multiple attempts.
 *
 * FR3, FR4: state.json v1 contract, DTOs and mappers.
 */
class StateJsonMapperSpec extends Specification {

    def startedAt = Instant.parse("2026-07-18T09:00:00Z")

    def "toDto maps version, position, attemptsUsed"() {
        given:
        def state = new TaskState(new Position.AtStage("implement"), 2, [], ExecutorUsage.none())

        when:
        def dto = StateJsonMapper.toDto(state)

        then:
        dto.version() == 1
        dto.position() == new StatePositionDto.AtStage("atStage", "implement")
        dto.attemptsUsed() == 2
        dto.attempts() == []
    }

    def "toDto maps PipelineEnd position"() {
        given:
        def state = new TaskState(new Position.PipelineEnd(), 0, [], ExecutorUsage.none())

        when:
        def dto = StateJsonMapper.toDto(state)

        then:
        dto.position() == new StatePositionDto.PipelineEnd("pipelineEnd")
    }

    def "toDto maps every AttemptRecord.Result to its lowerCamel discriminator"() {
        given:
        def record = new AttemptRecord(0, result, startedAt, [], ExecutorUsage.none(), JudgeUsage.none())
        def state = new TaskState(new Position.AtStage("implement"), 0, [record], ExecutorUsage.none())

        when:
        def dto = StateJsonMapper.toDto(state)

        then:
        dto.attempts()[0].result() == expected

        where:
        result                            | expected
        AttemptRecord.Result.PASSED           | "passed"
        AttemptRecord.Result.QUALITY_FAILURE  | "qualityFailure"
        AttemptRecord.Result.CANNOT_VERIFY    | "cannotVerify"
        AttemptRecord.Result.DECISION_NEEDED  | "decisionNeeded"
    }

    def "toDto flattens every Verdict kind onto StateCheckDto"() {
        given:
        def check = new CheckResult(new CheckRef(0, "command:./gradlew test"), verdict, Duration.ofMillis(500))
        def record = new AttemptRecord(0, AttemptRecord.Result.PASSED, startedAt, [check], ExecutorUsage.none(), JudgeUsage.none())
        def state = new TaskState(new Position.AtStage("implement"), 0, [record], ExecutorUsage.none())

        when:
        def dto = StateJsonMapper.toDto(state).attempts()[0].checks()[0]

        then:
        dto.ref() == "command:./gradlew test"
        dto.verdict() == expectedVerdict
        dto.findings() == expectedFindings
        dto.durationMillis() == 500
        dto.reason() == expectedReason
        dto.details() == expectedDetails

        where:
        verdict                                                              | expectedVerdict   | expectedFindings                                    | expectedReason | expectedDetails
        new Verdict.Pass()                                                   | "pass"            | []                                                   | null           | null
        new Verdict.Fail([
            new Finding("bad", "file.txt:12", "trace")
        ])       | "fail"             | [
            new StateFindingDto("bad", "file.txt:12", "trace")
        ] | null           | null
        new Verdict.Fail([])                                                 | "fail"             | []                                                   | null           | null
        new Verdict.CannotVerify("timeout", "network blip")                  | "cannotVerify"    | []                                                   | "timeout"      | "network blip"
    }

    def "round-trip: full TaskState with multiple attempts, checks, tokens, tools survives toDto/fromDto"() {
        given:
        def tokens = ["claude-sonnet": new TokenUsage(100, 50, 10, 5)]
        def usage = new ExecutorUsage(Duration.ofSeconds(30), [
            new ToolUsage("bash", 3, Duration.ofMillis(1200))
        ], tokens)
        def judgeUsage = new JudgeUsage([tokens, [:]])
        def check1 = new CheckResult(new CheckRef(0, "command:./gradlew test"), new Verdict.Pass(), Duration.ofMillis(200))
        def check2 = new CheckResult(
                new CheckRef(0, "judge:acceptance.md"),
                new Verdict.Fail([
                    new Finding("missing case", "Foo.java:10", null)
                ]),
                Duration.ofMillis(300))
        def attempt0 = new AttemptRecord(0, AttemptRecord.Result.QUALITY_FAILURE, startedAt, [check2], usage, JudgeUsage.none())
        def attempt1 = new AttemptRecord(1, AttemptRecord.Result.PASSED, startedAt.plusSeconds(60), [check1], usage, judgeUsage)
        def state = new TaskState(new Position.AtStage("implement"), 1, [attempt0, attempt1], usage.plus(usage))

        when:
        def dto = StateJsonMapper.toDto(state)
        def rebuilt = StateJsonMapper.fromDto(dto)

        then:
        rebuilt == state
    }

    def "round-trip: empty tokensByModel, empty tools, empty checks, no attempts survives"() {
        given:
        def state = TaskState.atStageStart("implement")

        when:
        def rebuilt = StateJsonMapper.fromDto(StateJsonMapper.toDto(state))

        then:
        rebuilt == state
    }

    def "round-trip: PipelineEnd position survives"() {
        given:
        def state = new TaskState(new Position.PipelineEnd(), 0, [], ExecutorUsage.none())

        when:
        def rebuilt = StateJsonMapper.fromDto(StateJsonMapper.toDto(state))

        then:
        rebuilt == state
    }

    def "round-trip: CannotVerify verdict survives with reason and details"() {
        given:
        def check = new CheckResult(new CheckRef(0, "external:ci"), new Verdict.CannotVerify("timeout", "poll exceeded"), Duration.ofMillis(1000))
        def record = new AttemptRecord(0, AttemptRecord.Result.CANNOT_VERIFY, startedAt, [check], ExecutorUsage.none(), JudgeUsage.none())
        def state = new TaskState(new Position.AtStage("verify"), 0, [record], ExecutorUsage.none())

        when:
        def rebuilt = StateJsonMapper.fromDto(StateJsonMapper.toDto(state))

        then:
        rebuilt == state
    }

    def "round-trip: DecisionNeeded round with no checks survives"() {
        given:
        def record = new AttemptRecord(0, AttemptRecord.Result.DECISION_NEEDED, startedAt, [], ExecutorUsage.none(), JudgeUsage.none())
        def state = new TaskState(new Position.AtStage("implement"), 0, [record], ExecutorUsage.none())

        when:
        def rebuilt = StateJsonMapper.fromDto(StateJsonMapper.toDto(state))

        then:
        rebuilt == state
    }

    def "round-trip via JSON: serialize then deserialize a full StateJsonDto tree is equal"() {
        given:
        def mapper = TaskStateJson.mapper()
        def state = new TaskState(
                new Position.AtStage("implement"),
                1,
                [
                    new AttemptRecord(
                    0,
                    AttemptRecord.Result.QUALITY_FAILURE,
                    startedAt,
                    [
                        new CheckResult(new CheckRef(0, "command:./gradlew test"), new Verdict.Fail([
                            new Finding("bad", null, null)
                        ]), Duration.ofMillis(400))
                    ],
                    new ExecutorUsage(Duration.ofSeconds(5), [], ["model-a": new TokenUsage(10, 20, 0, 0)]),
                    new JudgeUsage([
                        ["model-a": new TokenUsage(1, 2, 0, 0)]
                    ]))
                ],
                new ExecutorUsage(Duration.ofSeconds(5), [], ["model-a": new TokenUsage(10, 20, 0, 0)]))
        def dto = StateJsonMapper.toDto(state)

        when:
        def json = mapper.writeValueAsString(dto)
        def roundTripped = mapper.readValue(json, StateJsonDto)

        then:
        roundTripped == dto
        StateJsonMapper.fromDto(roundTripped) == state
    }

    def "round-trip via JSON: null wallMillis survives serialize/deserialize"() {
        given:
        def mapper = TaskStateJson.mapper()
        def dto = new StateJsonDto(1, new StatePositionDto.AtStage("atStage", "implement"), 0, [], new StateUsageDto(null, [:], []))

        when:
        def json = mapper.writeValueAsString(dto)
        def roundTripped = mapper.readValue(json, StateJsonDto)

        then:
        roundTripped == dto
        json.contains('"wallMillis":null')
    }

    def "round-trip via JSON: unknown fields in the JSON are ignored on read"() {
        given:
        def mapper = TaskStateJson.mapper()
        def json = '''
        {
          "version": 1,
          "position": {"type": "atStage", "stage": "implement"},
          "attemptsUsed": 0,
          "attempts": [],
          "totals": {"wallMillis": null, "tokensByModel": {}, "byTool": []},
          "someFutureField": "ignored"
        }
        '''

        when:
        def dto = mapper.readValue(json, StateJsonDto)

        then:
        dto.version() == 1
        dto.attemptsUsed() == 0
    }

    def "readDto parses a version-1 document, tolerating unknown fields"() {
        given:
        def json = '''
        {
          "version": 1,
          "position": {"type": "atStage", "stage": "implement"},
          "attemptsUsed": 0,
          "attempts": [],
          "totals": {"wallMillis": null, "tokensByModel": {}, "byTool": []},
          "someFutureField": "ignored"
        }
        '''

        when:
        def dto = StateJsonMapper.readDto(json)

        then:
        dto.version() == 1
        dto.attemptsUsed() == 0
    }

    def "readDto refuses an unsupported version before attempting to bind the DTO shape"() {
        given:
        // shape is incompatible with StateJsonDto (no position/attempts/totals) to
        // prove the version gate runs before any record binding is attempted
        def json = '{"version": 2, "somethingElseEntirely": true}'

        when:
        StateJsonMapper.readDto(json)

        then:
        def e = thrown(UnsupportedStateFileVersionException)
        e.message == "state.json: unsupported version 2 (supported: 1)"
        e.fileName() == "state.json"
        e.foundVersion() == 2
        e.supportedVersion() == 1
    }

    def "readDto refuses a document with no version field"() {
        given:
        def json = '{"attemptsUsed": 0}'

        when:
        StateJsonMapper.readDto(json)

        then:
        def e = thrown(UnsupportedStateFileVersionException)
        e.message == "state.json: missing (supported: 1)"
        e.foundVersion() == -1
    }

    def "toDto maps totals independently from attempts' per-round usage"() {
        given:
        def roundUsage = new ExecutorUsage(Duration.ofSeconds(1), [], [:])
        def totals = new ExecutorUsage(Duration.ofSeconds(99), [], ["model-a": new TokenUsage(1, 1, 0, 0)])
        def record = new AttemptRecord(0, AttemptRecord.Result.PASSED, startedAt, [], roundUsage, JudgeUsage.none())
        def state = new TaskState(new Position.AtStage("implement"), 0, [record], totals)

        when:
        def dto = StateJsonMapper.toDto(state)

        then:
        dto.totals().wallMillis() == 99000
        dto.attempts()[0].executorUsage().wallMillis() == 1000
    }
}
