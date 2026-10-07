package com.github.oinsio.gnomish.adapter.git

import spock.lang.Specification

/**
 * FR13 of make-checkpoint-gate-durable (design D10): the round token is a commit id in the one
 * form git prints it, because it is spelled into the decision file's name.
 */
class RoundTokenSpec extends Specification {

    def "FR13: a commit id is a round token, carried unchanged"() {
        expect:
        new RoundToken(commit).commit() == commit

        where:
        commit << [
            '0a1b2c3d4e5f60718293a4b5c6d7e8f901234567',
            '0a1b2c3d4e5f60718293a4b5c6d7e8f9012345670a1b2c3d4e5f60718293a4b5'
        ]
    }

    def "FR13: a blank or non-hex value is refused: #description"() {
        when:
        new RoundToken(commit)

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
}
