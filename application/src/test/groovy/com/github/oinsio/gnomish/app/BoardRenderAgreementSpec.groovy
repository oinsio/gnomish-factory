package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.board.BoardReferenceFixture
import com.github.oinsio.gnomish.board.json.BoardJsonMapper
import spock.lang.Specification

/**
 * Verifies the tracker-board spec's "Text and JSON agree" scenario: rendering {@link
 * BoardReferenceFixture#referenceModel} as text ({@link BoardTextRenderer}) and as JSON ({@link
 * BoardJsonMapper}) surfaces the same task ids, titles, annotations, and summary counts on both
 * surfaces. This is a spot-check over the spec's own concrete facts, not a full structural
 * equivalence checker — see {@code BoardReferenceJsonSpec} for the byte-identical JSON pin and
 * {@code BoardTextRendererSpec} for the text renderer's own per-scenario coverage.
 *
 * <p>Implements FR6, UX4, M1 of add-board-command.
 */
class BoardRenderAgreementSpec extends Specification {

    private final BoardTextRenderer renderer = new BoardTextRenderer()
    private final BoardJsonMapper mapper = new BoardJsonMapper()

    def model = BoardReferenceFixture.referenceModel()
    def text = renderer.render(model)
    def json = mapper.toDto(model, BoardReferenceFixture.WIP_LIMIT)

    def "every Ready row's id and title appear on both surfaces"() {
        expect:
        model.readyRows().every { row ->
            text.contains(row.ref().id()) && text.contains(row.title()) &&
            json.ready().rows().any {
                it.id() == row.ref().id() && it.title() == row.title()
            }
        }
    }

    def "the in-backoff deadline appears as the same instant on both surfaces"() {
        given:
        def backoffRow = json.ready().rows().find {
            it.eligibility().reason() == 'inBackoff'
        }

        expect:
        backoffRow.eligibility().deadline() == BoardReferenceFixture.BACKOFF_DEADLINE.toString()
        text.contains("in backoff until ${BoardReferenceFixture.BACKOFF_DEADLINE}")
    }

    def "the finished reason appears on both surfaces"() {
        given:
        def finishedRow = json.ready().rows().find {
            it.eligibility().reason() == 'finished'
        }

        expect:
        text.contains("${finishedRow.id()} - ${finishedRow.title()} — finished")
    }

    def "the WIP-held reason appears on both surfaces"() {
        given:
        def wipHeldRow = json.ready().rows().find {
            it.eligibility().reason() == 'wipHeld'
        }

        expect:
        text.contains("${wipHeldRow.id()} - ${wipHeldRow.title()} — WIP-held")
    }

    def "the returned marker appears on both surfaces"() {
        given:
        def returnedRow = json.ready().rows().find { it.returned() }

        expect:
        text.contains("${returnedRow.id()} - ${returnedRow.title()} (returned)")
    }

    def "Ready summary counts match between the JSON ready object and the text summary line"() {
        given:
        def readyDto = json.ready()

        expect:
        text.contains("${readyDto.queuedCount()} queued, ${readyDto.eligibleNowCount()} eligible, " +
                "${readyDto.inBackoffCount()} in backoff, ${readyDto.finishedCount()} finished, " +
                "${readyDto.wipHeldCount()} WIP-held")
    }

    def "Working holders and claim-freshness (age vs. unknown) appear on both surfaces"() {
        expect:
        model.workingRows().every { row ->
            text.contains("${row.ref().id()} - ${row.title()} (holder=${row.holder()}, ")
        }
        text.contains('holder=factory-a-1b2c, updated 3m ago')
        text.contains('holder=factory-b-9f00, freshness unknown')
        json.working().find { it.id() == 'github:g/w#1' }.claimUpdatedAt() ==
        BoardReferenceFixture.WORKING_CLAIM_UPDATED_AT.toString()
        json.working().find {
            it.id() == 'github:g/w#2'
        }.claimUpdatedAt() == null
    }

    def "AwaitingHuman park reasons appear on both surfaces"() {
        expect:
        model.awaitingHumanRows().every { row ->
            def label = json.awaitingHuman().find {
                it.id() == row.ref().id()
            }.parkReason()
            text.contains("${row.ref().id()} - ${row.title()} (reason=${label})")
        }
    }
}
