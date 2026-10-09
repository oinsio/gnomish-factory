package com.github.oinsio.gnomish.status.json

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.status.StatusReport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * The status-report wire tokens of the two sealed types {@code make-checkpoint-gate-durable}
 * adds to contract v1: the {@code position} (with {@code awaitingApproval}) and the attempt
 * {@code stop}. Every variant is iterated from {@code getPermittedSubclasses()}, never a
 * hand-listed subset (testing.md, wire-vocabulary rule): a new variant without a sample here
 * fails the coverage feature. The mapper only serializes; each variant's JSON is read back into
 * its DTO through the contract's own {@code type} discriminator, and its exact JSON is pinned.
 *
 * FR10, FR12, UX1 of make-checkpoint-gate-durable.
 */
class StatusReportVariantWireSpec extends Specification {

    static final Map<Class, Position> POSITIONS = [
        (Position.AtStage) : new Position.AtStage('implement'),
        (Position.AwaitingApproval): new Position.AwaitingApproval('release'),
        (Position.PipelineEnd) : new Position.PipelineEnd(),
    ]

    static final Map<Class, Stop> STOPS = [
        (Stop.None) : Stop.none(),
        (Stop.DecisionNeeded): new Stop.DecisionNeeded(UntrustedText.agent('Refactor or patch?'), [
            UntrustedText.agent('refactor'),
            UntrustedText.agent('patch')
        ]),
        (Stop.CannotVerify) : new Stop.CannotVerify(
        new CheckRef(1, UntrustedText.manifest('external:ci')),
        UntrustedText.subprocess('timeout'),
        UntrustedText.subprocess('poll exceeded 5m')),
    ]

    def json = StatusJson.mapper()
    def mapper = new StatusReportJsonMapper()

    def "FR12: every Position variant has a sample"() {
        expect:
        POSITIONS.keySet() == Position.permittedSubclasses.toList().toSet()
    }

    def "FR10: every Stop variant has a sample"() {
        expect:
        STOPS.keySet() == Stop.permittedSubclasses.toList().toSet()
    }

    def "FR12: position #variant.simpleName round-trips through its type discriminator"() {
        given:
        def dto = mapper.toDto(reportAt(POSITIONS[variant])).position()

        expect:
        json.readValue(json.writeValueAsString(dto), PositionDto) == dto

        where:
        variant << Position.permittedSubclasses.toList()
    }

    def "FR10: stop #variant.simpleName round-trips through its type discriminator"() {
        given:
        def dto = stopOf(STOPS[variant])

        expect:
        json.readValue(json.writeValueAsString(dto), StopDto) == dto

        where:
        variant << Stop.permittedSubclasses.toList()
    }

    def "FR12, UX1: the position tokens are atStage(stage), awaitingApproval(stage) and pipelineEnd"() {
        expect:
        json.writeValueAsString(mapper.toDto(reportAt(POSITIONS[variant])).position()) == expected

        where:
        variant | expected
        Position.AtStage | '{"type":"atStage","stage":"implement"}'
        Position.AwaitingApproval | '{"type":"awaitingApproval","stage":"release"}'
        Position.PipelineEnd | '{"type":"pipelineEnd"}'
    }

    def "FR10: the stop tokens are none, decisionNeeded(question, options) and cannotVerify(check, reason, details)"() {
        expect:
        json.writeValueAsString(stopOf(STOPS[variant])) == expected

        where:
        variant | expected
        Stop.None | '{"type":"none"}'
        Stop.DecisionNeeded | '{"type":"decisionNeeded","question":"Refactor or patch?","options":["refactor","patch"]}'
        Stop.CannotVerify | '{"type":"cannotVerify","check":"external:ci","reason":"timeout","details":"poll exceeded 5m"}'
    }

    def "FR10: the attempt carries its stop between result and startedAt"() {
        given:
        def attempt = json.readTree(mapper.serialize(reportWith(STOPS[Stop.DecisionNeeded])))
                .get('currentStage').get('attempts').get(0)

        expect:
        attempt.fieldNames().toList().take(4) == [
            'round',
            'result',
            'stop',
            'startedAt'
        ]
        attempt.get('stop').get('type').asText() == 'decisionNeeded'
    }

    private StopDto stopOf(Stop stop) {
        mapper.toDto(reportWith(stop)).currentStage().attempts()[0].stop()
    }

    private static StatusReport reportAt(Position position) {
        build(new TaskState(position, 0, [], ExecutorUsage.none()))
    }

    private static StatusReport reportWith(Stop stop) {
        def result = switch (stop) {
                    case Stop.DecisionNeeded -> AttemptRecord.Result.DECISION_NEEDED
                    case Stop.CannotVerify -> AttemptRecord.Result.CANNOT_VERIFY
                    default -> AttemptRecord.Result.QUALITY_FAILURE
                }
        def attempt = new AttemptRecord(
                0, result, Instant.parse('2026-07-16T14:35:10Z'), [], ExecutorUsage.none(), JudgeUsage.none(), [], stop)
        build(new TaskState(new Position.AtStage('implement'), 1, [attempt], ExecutorUsage.none()))
    }

    private static StatusReport build(TaskState state) {
        def context = new TaskContext('task-1', UntrustedText.tracker('Title'), UntrustedText.tracker('Body'), [])
        StatusReport.build(context, state, null, null)
    }
}
