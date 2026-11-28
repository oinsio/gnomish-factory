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
                "failed/limit", null, null, null)
    }

    /** An executor check's line: the check's index, and the key a resume took up when given. */
    static RoundOutcomeLine checkLine(UntrustedText resumedKey) {
        new RoundOutcomeLine(
                new InstanceInfo("gnomish-factory-x7k2q1", "worker-1.internal", "0.1.0-SNAPSHOT"),
                Instant.parse("2026-08-02T21:25:00Z"), "task-101", "verify", 2, "scm-status", null, "verdict",
                "failed/infrastructure", 1, resumedKey, null)
    }

    /** A pending stage round's line: the key it went pending with, or the key a resume took up. */
    static RoundOutcomeLine pendingRoundLine(UntrustedText pendingKey, UntrustedText resumedKey) {
        new RoundOutcomeLine(
                new InstanceInfo("gnomish-factory-x7k2q1", "worker-1.internal", "0.1.0-SNAPSHOT"),
                Instant.parse("2026-08-02T21:30:00Z"), "task-101", "build", 0, "remote-agent", null, "transform",
                "completed", null, resumedKey, pendingKey)
    }

    // UX3: version 1, the type discriminator, the round's key and the three words
    def "roundOutcome carries version 1, the type, the round's key, executor, role and status"() {
        when:
        def dto = RoundOutcomeLineMapper.toDto(limitRoundLine())

        then:
        dto == new RoundOutcomeLineDto(
                1, "roundOutcome", new InstanceDto("gnomish-factory-x7k2q1", "worker-1.internal", "0.1.0-SNAPSHOT"),
                "2026-08-02T21:20:00Z", "task-101", "verify", 2, "lint-bot@2.3.1", "transform", "failed/limit", null, null, null)
    }

    // NFR-O1, UX3 of migrate-verify-to-executor-contract: a check's line names its index and the resumed key
    def "an executor check's line carries the verdict role, the check's index and the resumed key verbatim"() {
        when:
        def dto = RoundOutcomeLineMapper.toDto(checkLine(UntrustedText.branchDocument('sonar-AY12...')))

        then:
        dto == new RoundOutcomeLineDto(
                1, "roundOutcome", new InstanceDto("gnomish-factory-x7k2q1", "worker-1.internal", "0.1.0-SNAPSHOT"),
                "2026-08-02T21:25:00Z", "task-101", "verify", 2, "scm-status", "verdict", "failed/infrastructure", 1,
                "sonar-AY12...", null)
        RoundOutcomeLineMapper.toDto(checkLine(null)).resumedKey() == null
    }

    // UX3 of extract-agent-round-runner: a pending transform round's line names the pending key, a
    //     resumed one the resumed key, each verbatim
    def "a pending stage round's line carries its pending key, a resumed one its resumed key"() {
        when:
        def live = RoundOutcomeLineMapper.toDto(pendingRoundLine(UntrustedText.subprocess('job-42'), null))
        def resumed = RoundOutcomeLineMapper.toDto(pendingRoundLine(null, UntrustedText.branchDocument('job-42')))

        then:
        live.role() == 'transform'
        live.pendingKey() == 'job-42'
        live.resumedKey() == null
        resumed.pendingKey() == null
        resumed.resumedKey() == 'job-42'
    }

    // UX3 of migrate-verify-to-executor-contract: the key is the executor's text, escaped into one line
    def "a resumed key carrying a quote stays one escaped JSON line"() {
        expect:
        mapper.serialize(checkLine(UntrustedText.branchDocument('k"1'))).contains('"resumedKey":"k\\"1"')
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
