package com.github.oinsio.gnomish.serveobservability.json

import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.LedgerLifecycleEvent
import com.github.oinsio.gnomish.serveobservability.LedgerTokenUsage
import com.github.oinsio.gnomish.serveobservability.LifecycleLine
import com.github.oinsio.gnomish.serveobservability.OutcomeCounts
import com.github.oinsio.gnomish.serveobservability.RunSummaryLine
import com.github.oinsio.gnomish.serveobservability.SweepActionLine
import com.github.oinsio.gnomish.serveobservability.SweepCounts
import com.github.oinsio.gnomish.serveobservability.SweepTickLine
import com.github.oinsio.gnomish.serveobservability.TaskOutcome
import com.github.oinsio.gnomish.serveobservability.TaskOutcomeLine
import java.time.Duration
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

    def "reference anchor: serializing the deterministic samples is byte-identical to ledger-v1.reference.jsonl"() {
        given:
        def referenceLines = getClass().getResourceAsStream('/ledger-v1.reference.jsonl').getText('UTF-8').split('\n')

        expect:
        mapper.serialize(deliveredLine()) == referenceLines[0]
        mapper.serialize(awaitingHumanLine()) == referenceLines[1]
        mapper.serialize(startedLine()) == referenceLines[2]
        mapper.serialize(stoppedLine()) == referenceLines[3]
        mapper.serialize(runSummaryLine()) == referenceLines[4]
        mapper.serialize(sweepActionLine()) == referenceLines[5]
        mapper.serialize(sweepTickLine()) == referenceLines[6]
    }

    // NFR-O2 of add-serve-sandbox-lifecycle.
    def "sweepAction carries version 1, the type discriminator, and every verdict field"() {
        given:
        def dto = mapper.toDto(sweepActionLine())

        expect:
        dto.version() == 1
        dto.type() == "sweepAction"
        dto.instance() == instanceDto()
        dto.at() == "2026-08-02T21:00:00Z"
        dto.objectName() == "gnomish-task-99-box"
        dto.role() == "main-box"
        dto.mode() == "tracked"
        dto.taskKey() == "task-99"
        dto.category() == "stoppedOrphan"
        dto.reason() == "unowned running main-box"
        dto.ageSeconds() == 900L
    }

    // NFR-O2: the verdict category vocabulary is the SAME wire vocabulary the snapshot's
    //     vitals.sweep.counts keys use, so no reader has to reconcile near-synonyms (FR9).
    def "sweepAction category serializes each acting variant to its lowerCamel wire value"() {
        expect:
        mapper.toDto(sweepActionWith(category)).category() == wireValue

        where:
        category | wireValue
        SweepVerdictCategory.STOPPED_ORPHAN | "stoppedOrphan"
        SweepVerdictCategory.DISPOSED_AGED | "disposedAged"
        SweepVerdictCategory.DISPOSED_RECONSTRUCTIBLE | "disposedReconstructible"
    }

    // NFR-O2: a verdict that measured no age renders ageSeconds as explicit null, never omitted.
    def "sweepAction ageSeconds is null when the verdict measured no age"() {
        expect:
        mapper.toDto(sweepActionLineWithoutAge()).ageSeconds() == null
        mapper.serialize(sweepActionLineWithoutAge()).contains('"ageSeconds":null')
    }

    // NFR-O2: the per-tick summary carries all six counts, including the untouched categories that
    //     are never itemized as their own lines.
    def "sweepTick carries version 1, the type discriminator, and all six counts"() {
        given:
        def dto = mapper.toDto(sweepTickLine())

        expect:
        dto.version() == 1
        dto.type() == "sweepTick"
        dto.at() == "2026-08-02T21:05:00Z"
        dto.counts() == new SweepCountsDto(4, 2, 1, 1, 3, 0)
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

    // The reason the wiring writes today is `ServeShutdownWiring.SIGNAL_REASON`; this sample keeps
    // the older `sigterm` wording on purpose. It feeds the frozen `ledger-v1.reference.jsonl`
    // anchor, and the point of that anchor is that a reader parses a v1 line whatever reason text
    // it carries — the field is opaque to the wire format, which is what made the rename safe.
    static LifecycleLine stoppedLine() {
        new LifecycleLine(
                instance(), Instant.parse("2026-08-02T23:00:00Z"), new LedgerLifecycleEvent.Stopped("sigterm"))
    }

    static SweepActionLine sweepActionLine() {
        sweepActionWith(SweepVerdictCategory.STOPPED_ORPHAN)
    }

    static SweepActionLine sweepActionWith(SweepVerdictCategory category) {
        new SweepActionLine(
                instance(), Instant.parse("2026-08-02T21:00:00Z"), "gnomish-task-99-box", "main-box", "tracked",
                "task-99", category, "unowned running main-box", Duration.ofMinutes(15))
    }

    static SweepActionLine sweepActionLineWithoutAge() {
        new SweepActionLine(
                instance(), Instant.parse("2026-08-02T21:00:00Z"), "gnomish-task-99-box", "main-box", "tracked",
                "task-99", SweepVerdictCategory.STOPPED_ORPHAN, "unowned running main-box", null)
    }

    static SweepTickLine sweepTickLine() {
        new SweepTickLine(
                instance(), Instant.parse("2026-08-02T21:05:00Z"), new SweepCounts(4, 2, 1, 1, 3, 0))
    }

    static RunSummaryLine runSummaryLine() {
        new RunSummaryLine(
                instance(), Instant.parse("2026-08-02T07:00:05Z"), Instant.parse("2026-08-02T22:30:05Z"), 55800000L,
                new OutcomeCounts(3, 1, 0, 0),
                ["claude-sonnet-5": new LedgerTokenUsage(5000, 900, 100, 40)])
    }
}
