package com.github.oinsio.gnomish.board.json

import com.github.oinsio.gnomish.board.BoardReferenceFixture
import spock.lang.Specification

/**
 * Verifies {@link BoardJsonMapper} against the deterministic {@code board-v1.reference.json}
 * fixture — the sibling of {@code StatusReportJsonMapperSpec}'s reference-anchor test for the
 * board (task 4.3): a byte-identical comparison pins the v1 JSON document shape so an
 * unintentional field rename, ordering change, or precision drift fails loudly.
 *
 * <p>{@link BoardReferenceFixture#referenceModel} is the same deterministic sample used by
 * {@code BoardRenderAgreementSpec} to check the spec's "Text and JSON agree" scenario, so both
 * specs stay anchored to one shared shape.
 *
 * <p>Implements FR6, UX4, M1 of add-board-command.
 */
class BoardReferenceJsonSpec extends Specification {

    def mapper = new BoardJsonMapper()

    def "reference anchor: serializing the deterministic sample is byte-identical to board-v1.reference.json"() {
        given:
        def referenceText = getClass().getResourceAsStream('/board-v1.reference.json').getText('UTF-8')

        expect:
        mapper.serialize(BoardReferenceFixture.referenceModel(), BoardReferenceFixture.WIP_LIMIT) == referenceText
    }
}
