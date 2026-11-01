package com.github.oinsio.gnomish.serveobservability.json

import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.RoundOutcomeLine
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * NFR-O2, UX3 of define-executor-contract: the {@code roundOutcome} ledger line's JSON shape —
 * {@code executor=<name>@<version> role=<role> status=<status>[/<class>]} beside the round's key —
 * and the executor's version written verbatim to the machine medium, escaped into one line.
 */
class RoundOutcomeLineMapperSpec extends Specification {

    def mapper = new LedgerJsonMapper()

    static RoundOutcomeLine limitRoundLine() {
        roundLine(UntrustedText.subprocess('2.3.1'))
    }

    static RoundOutcomeLine roundLine(UntrustedText version) {
        new RoundOutcomeLine(
                new InstanceInfo("gnomish-factory-x7k2q1", "worker-1.internal", "0.1.0-SNAPSHOT"),
                Instant.parse("2026-08-02T21:20:00Z"), "task-101", "verify", 2, "lint-bot", version, "transform",
                "failed/limit")
    }

    // UX3: version 1, the type discriminator, the round's key and the three words
    def "roundOutcome carries version 1, the type, the round's key, executor, role and status"() {
        when:
        def dto = RoundOutcomeLineMapper.toDto(limitRoundLine())

        then:
        dto == new RoundOutcomeLineDto(
                1, "roundOutcome", new InstanceDto("gnomish-factory-x7k2q1", "worker-1.internal", "0.1.0-SNAPSHOT"),
                "2026-08-02T21:20:00Z", "task-101", "verify", 2, "lint-bot@2.3.1", "transform", "failed/limit")
    }

    // UX3: an executor with no describe is named alone
    def "an undescribed executor is named without a version"() {
        expect:
        RoundOutcomeLineMapper.toDto(roundLine(null)).executor() == "lint-bot"
    }

    // NFR-S2: the executor's own text stays one JSON line, its bytes escaped, not rendered
    def "a version carrying a newline and a quote stays one escaped JSON line"() {
        when:
        def json = mapper.serialize(roundLine(UntrustedText.subprocess('2"\nx')))

        then:
        !json.contains('\n')
        json.contains('"executor":"lint-bot@2\\"\\nx"')
    }
}
