package com.github.oinsio.gnomish.adapter.git

import spock.lang.Specification

/**
 * FR13 of make-checkpoint-gate-durable (design D10): the per-run carrier of the current round's
 * token, the {@code AttemptCommitRef} shape.
 */
class RoundTokenRefSpec extends Specification {

    static final RoundToken FIRST = new RoundToken('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')
    static final RoundToken SECOND = new RoundToken('bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb')

    def "FR13: required refuses before any round opened"() {
        when:
        new RoundTokenRef().required()

        then:
        def refused = thrown(IllegalStateException)
        refused.message.contains('no round token recorded')
    }

    def "FR13: required answers the token the last opened round recorded"() {
        given:
        def ref = new RoundTokenRef()

        when:
        ref.record(FIRST)

        then:
        ref.required() == FIRST

        when: 'the next round opens'
        ref.record(SECOND)

        then: 'its token replaces the previous round\'s'
        ref.required() == SECOND
    }
}
