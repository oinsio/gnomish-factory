package com.github.oinsio.gnomish.app.port.git

import spock.lang.Specification

/**
 * FR21, design D15 of add-sandbox-core: the shared holder carrying the round-closing snapshot
 * commit from the executor to everything that verifies or persists that round. One instance lives
 * for a whole task run, so each round overwrites the previous one's commit, and reading before any
 * snapshot was recorded is a protocol violation rather than a null.
 *
 * Added by task 8.7 of split-into-modules (design D13(c)).
 */
class AttemptCommitRefSpec extends Specification {

    // FR21: the recorded commit is what every consumer of the ref observes.
    def "returns the recorded attempt commit"() {
        given:
        def ref = new AttemptCommitRef()

        when:
        ref.record('abc123')

        then:
        ref.required() == 'abc123'
    }

    // FR21, D15: one ref lives for the whole run and the snapshot step re-records each round, so a
    // later round's commit replaces the earlier one — consumers always see the CURRENT round.
    def "a later round's commit replaces the previous round's"() {
        given:
        def ref = new AttemptCommitRef()

        when:
        ref.record('round-one')
        ref.record('round-two')

        then:
        ref.required() == 'round-two'
    }

    // D15: verifying or persisting without a round-closing snapshot is a protocol violation by
    // construction — it fails loudly here rather than handing a null sha downstream.
    def "refuses to yield a commit before any snapshot was recorded"() {
        given:
        def ref = new AttemptCommitRef()

        when:
        ref.required()

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('no attempt commit recorded')
    }
}
