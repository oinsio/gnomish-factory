package com.github.oinsio.gnomish.serveobservability.json

import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.LedgerLifecycleEvent
import com.github.oinsio.gnomish.serveobservability.LedgerTokenUsage
import com.github.oinsio.gnomish.serveobservability.LifecycleLine
import com.github.oinsio.gnomish.serveobservability.OutcomeCounts
import com.github.oinsio.gnomish.serveobservability.RunSummaryLine
import com.github.oinsio.gnomish.serveobservability.TaskOutcome
import com.github.oinsio.gnomish.serveobservability.TaskOutcomeLine
import java.time.Instant
import spock.lang.Specification

/**
 * Verifies {@link LedgerJsonMapper} against the v1 ledger JSON contract
 * (spec.md): status-report v1 conventions (camelCase, ISO-8601 UTC instants,
 * explicit {@code null} rather than omission), every sealed/enum branch, and
 * the {@code ledger-v1.reference.jsonl} byte-identity anchor, line by line.
 *
 * FR10, FR11, FR12, FR13 of add-serve-observability.
 */
class LedgerJsonMapperSpec extends Specification {

    def mapper = new LedgerJsonMapper()

    def "reference anchor: serializing the three deterministic samples is byte-identical to ledger-v1.reference.jsonl"() {
        given:
        def referenceLines = getClass().getResourceAsStream('/ledger-v1.reference.jsonl').getText('UTF-8').split('\n')

        expect:
        mapper.serialize(deliveredLine()) == referenceLines[0]
        mapper.serialize(awaitingHumanLine()) == referenceLines[1]
        mapper.serialize(startedLine()) == referenceLines[2]
        mapper.serialize(stoppedLine()) == referenceLines[3]
        mapper.serialize(runSummaryLine()) == referenceLines[4]
    }

    def "taskOutcome carries version 1 and type discriminator"() {
        expect:
        mapper.toDto(deliveredLine()).version() == 1
        mapper.toDto(deliveredLine()).type() == "taskOutcome"
    }

    def "taskOutcome outcome serializes each variant to its lowerCamel wire value"() {
        expect:
        mapper.toDto(lineWithOutcome(outcome)).outcome() == wireValue

        where:
        outcome | wireValue
        TaskOutcome.DELIVERED | "delivered"
        TaskOutcome.AWAITING_HUMAN | "awaitingHuman"
        TaskOutcome.ABORTED | "aborted"
        TaskOutcome.REVOKED | "revoked"
    }

    def "parkReason is null when outcome is not awaitingHuman"() {
        expect:
        mapper.toDto(deliveredLine()).parkReason() == null
    }

    def "parkReason serializes each ParkReason variant to its lowerCamel wire value when outcome is awaitingHuman"() {
        expect:
        mapper.toDto(awaitingHumanLineWith(reason)).parkReason() == wireValue

        where:
        reason | wireValue
        ParkReason.ESCALATION | "escalation"
        ParkReason.CHECKPOINT | "checkpoint"
        ParkReason.INFRA | "infra"
    }

    def "stage renders null at pipeline end"() {
        expect:
        mapper.toDto(deliveredLine()).stage() == null
    }

    def "taskOutcome carries taskId, attemptsUsed, timestamps, wallMillis, and tokensByModel"() {
        given:
        def dto = mapper.toDto(deliveredLine())

        expect:
        dto.taskId() == "task-100"
        dto.attemptsUsed() == 2
        dto.startedAt() == "2026-08-02T20:00:00Z"
        dto.finishedAt() == "2026-08-02T20:05:30Z"
        dto.wallMillis() == 330000L
        dto.tokensByModel() == ["claude-sonnet-5": new TokenUsageDto(1000, 200, 50, 10)]
    }

    def "lifecycle started renders event with no reason"() {
        expect:
        mapper.toDto(startedLine()) ==
                new LifecycleLineDto(1, "lifecycle", instanceDto(), "2026-08-02T07:00:00Z", "started", null)
    }

    def "lifecycle stopped renders event and reason"() {
        expect:
        mapper.toDto(stoppedLine()) ==
                new LifecycleLineDto(1, "lifecycle", instanceDto(), "2026-08-02T23:00:00Z", "stopped", "sigterm")
    }

    def "runSummary carries version 1, type, counts, and summed tokensByModel"() {
        given:
        def dto = mapper.toDto(runSummaryLine())

        expect:
        dto.version() == 1
        dto.type() == "runSummary"
        dto.counts() == new OutcomeCountsDto(3, 1, 0, 0)
        dto.tokensByModel() == ["claude-sonnet-5": new TokenUsageDto(5000, 900, 100, 40)]
        dto.wallMillis() == 55800000L
    }

    private static InstanceDto instanceDto() {
        new InstanceDto("gnomish-factory-x7k2q1", "worker-1.internal", "0.1.0-SNAPSHOT")
    }

    private static InstanceInfo instance() {
        new InstanceInfo("gnomish-factory-x7k2q1", "worker-1.internal", "0.1.0-SNAPSHOT")
    }

    static TaskOutcomeLine deliveredLine() {
        new TaskOutcomeLine(
                instance(), "task-100", TaskOutcome.DELIVERED, null, null, 2,
                Instant.parse("2026-08-02T20:00:00Z"), Instant.parse("2026-08-02T20:05:30Z"), 330000L,
                ["claude-sonnet-5": new LedgerTokenUsage(1000, 200, 50, 10)])
    }

    static TaskOutcomeLine lineWithOutcome(TaskOutcome outcome) {
        def parkReason = outcome == TaskOutcome.AWAITING_HUMAN ? ParkReason.ESCALATION : null
        new TaskOutcomeLine(
                instance(), "task-100", outcome, parkReason, null, 2,
                Instant.parse("2026-08-02T20:00:00Z"), Instant.parse("2026-08-02T20:05:30Z"), 330000L, [:])
    }

    static TaskOutcomeLine awaitingHumanLine() {
        awaitingHumanLineWith(ParkReason.ESCALATION)
    }

    static TaskOutcomeLine awaitingHumanLineWith(ParkReason reason) {
        new TaskOutcomeLine(
                instance(), "task-101", TaskOutcome.AWAITING_HUMAN, reason, "verify", 3,
                Instant.parse("2026-08-02T20:10:00Z"), Instant.parse("2026-08-02T20:20:00Z"), 600000L,
                ["claude-sonnet-5": new LedgerTokenUsage(2000, 400, 0, 0)])
    }

    static LifecycleLine startedLine() {
        new LifecycleLine(instance(), Instant.parse("2026-08-02T07:00:00Z"), new LedgerLifecycleEvent.Started())
    }

    static LifecycleLine stoppedLine() {
        new LifecycleLine(
                instance(), Instant.parse("2026-08-02T23:00:00Z"), new LedgerLifecycleEvent.Stopped("sigterm"))
    }

    static RunSummaryLine runSummaryLine() {
        new RunSummaryLine(
                instance(), Instant.parse("2026-08-02T07:00:05Z"), Instant.parse("2026-08-02T22:30:05Z"), 55800000L,
                new OutcomeCounts(3, 1, 0, 0),
                ["claude-sonnet-5": new LedgerTokenUsage(5000, 900, 100, 40)])
    }
}
