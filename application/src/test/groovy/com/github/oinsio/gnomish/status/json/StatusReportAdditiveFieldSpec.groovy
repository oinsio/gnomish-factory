package com.github.oinsio.gnomish.status.json

import static com.github.oinsio.gnomish.status.StatusReportReferenceFixture.referenceReport

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification

/**
 * FR11 of add-manual-run (versioning policy); FR4 of fix-denial-report-attachment; FR2 of
 * fix-denial-attribution-durability; FR12 of make-checkpoint-gate-durable: fields added to contract
 * v1 after it was first defined — the attempt {@code denials}, the escalation {@code denials}, the
 * attempt {@code stop} — joined the document without moving its version. The pinned reference
 * document is the evidence: it carries every one of them, and it still says {@code 1}.
 */
class StatusReportAdditiveFieldSpec extends Specification {

    private static Map document(String json) {
        new ObjectMapper().readValue(json, Map)
    }

    // Scenario: observability/status-report / An additive field keeps the version
    def "FR11: the fields added under contract v1 are in the document, and the version is still 1"() {
        given: 'the committed reference document and the serializer\'s own output for its sample'
        def reference = document(getClass().getResourceAsStream('/status-report-v1.reference.json').getText('UTF-8'))
        def serialized = document(new StatusReportJsonMapper().serialize(referenceReport()))

        expect: 'the additive fields are part of the shape both carry'
        [reference, serialized].every { doc ->
            def attempt = doc.currentStage.attempts.first()
            attempt.containsKey('denials') && attempt.containsKey('stop') && attempt.containsKey('judgeUsage')
        }

        and: 'and neither document moved off version 1 to carry them'
        reference.version == 1
        serialized.version == 1
    }
}
