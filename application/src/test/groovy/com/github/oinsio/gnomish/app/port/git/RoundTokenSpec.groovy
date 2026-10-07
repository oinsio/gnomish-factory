package com.github.oinsio.gnomish.app.port.git

import spock.lang.Specification

/**
 * FR13 of make-checkpoint-gate-durable (design D10): the round token is a commit id in the one
 * form git prints it, because it is spelled into the decision file's name, and {@code
 * RoundToken.of} is the one parse that makes one.
 */
class RoundTokenSpec extends Specification {

    def "FR13: a commit id is a round token, carried unchanged"() {
        expect:
        RoundToken.of(commit).commit() == commit

        where:
        commit << [
            '0a1b2c3d4e5f60718293a4b5c6d7e8f901234567',
            '0a1b2c3d4e5f60718293a4b5c6d7e8f9012345670a1b2c3d4e5f60718293a4b5'
        ]
    }

    def "FR13: a blank or non-hex value is refused: #description"() {
        when:
        RoundToken.of(commit)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains('round token')

        where:
        description | commit
        'empty' | ''
        'blank' | '   '
        'non-hex letter' | '0a1b2g'
        'uppercase spelling' | '0A1B2C'
        'trailing newline' | '0a1b2c\n'
        'path separator' | '0a1b/../x'
    }

    def "FR13: two parses of one commit id are one identity, and another commit id is another"() {
        expect:
        RoundToken.of('0a1b2c') == RoundToken.of('0a1b2c')
        RoundToken.of('0a1b2c').hashCode() == RoundToken.of('0a1b2c').hashCode()
        RoundToken.of('0a1b2c') != RoundToken.of('0a1b2d')
        RoundToken.of('0a1b2c').hashCode() == '0a1b2c'.hashCode()
        !RoundToken.of('0a1b2c').equals('0a1b2c')
    }

    def "FR13: the token renders with its commit id for diagnostics"() {
        expect:
        RoundToken.of('0a1b2c').toString() == 'RoundToken[0a1b2c]'
    }
}
