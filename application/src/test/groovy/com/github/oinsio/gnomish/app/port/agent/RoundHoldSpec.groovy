package com.github.oinsio.gnomish.app.port.agent

import spock.lang.Specification

/**
 * FR10 of extract-agent-round-runner (design D14 as amended 2026-11-25): {@link RoundHold}, the one
 * way a round source holds its open round — one at a time, let go at the close or the discard, and
 * remembering whether it ever held one.
 */
class RoundHoldSpec extends Specification {

    def hold = new RoundHold<String>()

    def "a fresh hold holds nothing, never held anything, and lets an open through"() {
        when:
        hold.refuseOpenWhileHeld()

        then:
        noExceptionThrown()
        hold.held() == Optional.empty()
        !hold.everHeld()
    }

    def "a held round is handed back, remembered, and refuses the next open"() {
        when:
        def held = hold.hold('round-1')

        then:
        held == 'round-1'
        hold.held() == Optional.of('round-1')
        hold.everHeld()

        when:
        hold.refuseOpenWhileHeld()

        then:
        def refused = thrown(IllegalStateException)
        refused.message.contains('second open is refused')
    }

    def "a second round cannot be held beside the first"() {
        given:
        hold.hold('round-1')

        when:
        hold.hold('round-2')

        then:
        thrown(IllegalStateException)
        hold.held() == Optional.of('round-1')
    }

    def "the released round is let go, a stale release changes nothing, and the history stays"() {
        given:
        hold.hold('round-1')

        when:
        hold.release('round-1')

        then:
        hold.held() == Optional.empty()
        hold.everHeld()

        when:
        hold.hold('round-2')
        hold.release('round-1')

        then:
        hold.held() == Optional.of('round-2')
    }
}
