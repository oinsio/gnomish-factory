package com.github.oinsio.gnomish.app.port.agent

import spock.lang.Specification

/**
 * FR10 of extract-agent-round-runner (design D11 as amended 2026-11-25): a round's close takes a
 * terminal outcome only, and each names the snapshot subject it closes under — never the pending one.
 */
class TerminalCloseSpec extends Specification {

    def "#close closes under the #snapshot snapshot"() {
        expect:
        close.snapshot() == snapshot

        where:
        close | snapshot
        TerminalClose.VERIFIABLE | RoundClose.VERIFIABLE
        TerminalClose.UNVERIFIABLE | RoundClose.UNVERIFIABLE
    }

    def "no terminal close is the pending one"() {
        expect:
        TerminalClose.values().every { it.snapshot() != RoundClose.PENDING }
        TerminalClose.values().size() == RoundClose.values().size() - 1
    }
}
