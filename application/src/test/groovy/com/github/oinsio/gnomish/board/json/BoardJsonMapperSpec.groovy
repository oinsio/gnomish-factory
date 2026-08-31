package com.github.oinsio.gnomish.board.json

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.BackoffPolicy
import com.github.oinsio.gnomish.board.BoardModel
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * Verifies {@link BoardJsonMapper} against the board JSON contract (v1, spec.md
 * "Dual text and JSON rendering of one model"): version/generatedAt presence,
 * eligible vs. each ineligible reason's JSON shape, Working rows with/without a
 * claim version, and AwaitingHuman park reasons.
 *
 * FR6, NFR-O1: JSON contract v1, self-describing eligibility and claim facts.
 */
class BoardJsonMapperSpec extends Specification {

    private static final Instant NOW = Instant.parse('2026-08-05T09:00:00Z')
    private static final Duration BASE = BackoffPolicy.DEFAULT_BASE
    private static final Duration CAP = BackoffPolicy.DEFAULT_CAP

    def mapper = new BoardJsonMapper()

    private static ReadyTask readyTask(String id, AbortFacts abortFacts, boolean returned, boolean finished) {
        new ReadyTask(new TaskRef(id), abortFacts, returned, finished, "title for ${id}")
    }

    def "top-level document carries version 1, generatedAt, and truncated"() {
        given:
        def model = BoardModel.build([], [], true, NOW)

        when:
        def dto = mapper.toDto(model, 5)

        then:
        dto.version() == 1
        dto.generatedAt() == NOW.toString()
        dto.truncated()
    }

    def "an eligible Ready row renders eligible true with no reason or deadline"() {
        given:
        def ready = [
            readyTask('github:o/r#1', AbortFacts.none(), false, false)
        ]
        def model = BoardModel.build(ready, [], false, NOW, BASE, CAP, NOW, 0, 3)

        when:
        def row = mapper.toDto(model, 3).ready().rows()[0]

        then:
        row.id() == 'github:o/r#1'
        row.title() == 'title for github:o/r#1'
        !row.returned()
        row.eligibility() == new EligibilityDto(true, null, null)
    }

    def "an in-backoff Ready row renders the materialized deadline"() {
        given:
        def facts = new AbortFacts(1, NOW - Duration.ofMinutes(1))
        def deadline = facts.lastAbortAt() + BackoffPolicy.delay(1, BASE, CAP)
        def ready = [
            readyTask('github:o/r#2', facts, false, false)
        ]
        def model = BoardModel.build(ready, [], false, NOW, BASE, CAP, NOW, 0, 3)

        when:
        def eligibility = mapper.toDto(model, 3).ready().rows()[0].eligibility()

        then:
        eligibility == new EligibilityDto(false, 'inBackoff', deadline.toString())
    }

    def "a finished Ready row renders reason finished with no deadline"() {
        given:
        def ready = [
            readyTask('github:o/r#3', AbortFacts.none(), false, true)
        ]
        def model = BoardModel.build(ready, [], false, NOW, BASE, CAP, NOW, 0, 3)

        when:
        def eligibility = mapper.toDto(model, 3).ready().rows()[0].eligibility()

        then:
        eligibility == new EligibilityDto(false, 'finished', null)
    }

    def "a WIP-held Ready row renders reason wipHeld, and openFrontCount/wipLimit are materialized"() {
        given:
        def ready = [
            readyTask('github:o/r#4', AbortFacts.none(), false, false)
        ]
        def open = [
            new OpenTask(new TaskRef('github:o/r#10'), new TrackerTaskState.Working('holder-a'), null, 'working title')
        ]
        def model = BoardModel.build(ready, open, false, NOW, BASE, CAP, NOW, 3, 3)

        when:
        def dto = mapper.toDto(model, 3)

        then:
        dto.ready().rows()[0].eligibility() == new EligibilityDto(false, 'wipHeld', null)
        dto.ready().openFrontCount() == 1
        dto.ready().wipLimit() == 3
    }

    def "ready summary counts are carried through from ReadySummary"() {
        given:
        def ready = [
            readyTask('github:o/r#5', AbortFacts.none(), false, false)
        ]
        def model = BoardModel.build(ready, [], false, NOW, BASE, CAP, NOW, 0, 3)

        when:
        def readyDto = mapper.toDto(model, 3).ready()

        then:
        readyDto.queuedCount() == 1
        readyDto.eligibleNowCount() == 1
        readyDto.inBackoffCount() == 0
        readyDto.finishedCount() == 0
        readyDto.wipHeldCount() == 0
    }

    def "a Working row with a live claim marker renders claimUpdatedAt as an ISO instant"() {
        given:
        def updatedAt = NOW - Duration.ofMinutes(3)
        def open = [
            new OpenTask(new TaskRef('github:o/r#20'), new TrackerTaskState.Working('holder-b'),
            new ClaimVersion('marker-1', updatedAt, new ClaimEpoch(1)), 'working title')
        ]
        def model = BoardModel.build([], open, false, NOW)

        when:
        def row = mapper.toDto(model, 3).working()[0]

        then:
        row.id() == 'github:o/r#20'
        row.holder() == 'holder-b'
        row.claimUpdatedAt() == updatedAt.toString()
    }

    def "a Working row with an absent claim marker renders claimUpdatedAt as explicit JSON null"() {
        given:
        def open = [
            new OpenTask(new TaskRef('github:o/r#21'), new TrackerTaskState.Working('holder-c'), null, 'working title')
        ]
        def model = BoardModel.build([], open, false, NOW)

        when:
        def json = mapper.serialize(model, 3)
        def row = mapper.toDto(model, 3).working()[0]

        then:
        row.claimUpdatedAt() == null
        json.contains('"claimUpdatedAt" : null')
    }

    def "AwaitingHuman rows render lowercase park-reason labels"() {
        given:
        def open = [
            new OpenTask(new TaskRef('github:o/r#30'), new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION), null, 'escalated title'),
            new OpenTask(new TaskRef('github:o/r#31'), new TrackerTaskState.AwaitingHuman(ParkReason.INFRA), null, 'infra title'),
            new OpenTask(new TaskRef('github:o/r#32'), new TrackerTaskState.AwaitingHuman(ParkReason.CHECKPOINT), null, 'checkpoint title')
        ]
        def model = BoardModel.build([], open, false, NOW)

        when:
        def rows = mapper.toDto(model, 3).awaitingHuman()

        then:
        rows*.parkReason() == [
            'escalation',
            'infra',
            'checkpoint'
        ]
    }

    def "serialize produces pretty-printed JSON containing the version field"() {
        given:
        def model = BoardModel.build([], [], false, NOW)

        when:
        def json = mapper.serialize(model, 3)

        then:
        json.contains('"version" : 1')
        json.contains('"generatedAt" : "' + NOW + '"')
    }
}
