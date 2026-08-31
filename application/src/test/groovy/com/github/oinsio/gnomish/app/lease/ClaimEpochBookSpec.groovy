package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import spock.lang.Specification

/**
 * ClaimEpochBook: the instance's record of the epochs its live tenures were issued
 * (FR13 of harden-task-branch-contract).
 */
class ClaimEpochBookSpec extends Specification {

    def book = new ClaimEpochBook()

    // FR13: a writer asks the book which tenure it is stamping for
    def "answers the epoch recorded for a task"() {
        given:
        book.issued('PROJ-1', new ClaimEpoch(7))

        expect:
        book.epochFor('PROJ-1').orElse(null) == new ClaimEpoch(7)
    }

    // FR13: a task this instance never claimed has no epoch — the book never invents one
    def "answers empty for a task it never recorded"() {
        expect:
        book.epochFor('PROJ-1').isEmpty()
    }

    // FR13: a reclaim after a reap supersedes what this instance held before
    def "a later claim of the same task replaces the earlier epoch"() {
        given:
        book.issued('PROJ-1', new ClaimEpoch(7))
        book.issued('PROJ-1', new ClaimEpoch(11))

        expect:
        book.epochFor('PROJ-1').orElse(null) == new ClaimEpoch(11)
    }

    // FR13: a stamp made after the claim was dropped is exactly what the fence exists to catch
    def "forgets a tenure that ended"() {
        given:
        book.issued('PROJ-1', new ClaimEpoch(7))

        when:
        book.ended('PROJ-1')

        then:
        book.epochFor('PROJ-1').isEmpty()
    }

    // FR13: ending a tenure twice, or one never recorded, changes nothing
    def "ending an unrecorded tenure is a no-op"() {
        when:
        book.ended('PROJ-1')
        book.ended('PROJ-1')

        then:
        book.epochFor('PROJ-1').isEmpty()
    }

    // FR13: one instance holds several tenures at once under serve — they never bleed into each other
    def "keeps the tenures of different tasks apart"() {
        given:
        book.issued('PROJ-1', new ClaimEpoch(7))
        book.issued('PROJ-2', new ClaimEpoch(8))

        when:
        book.ended('PROJ-1')

        then:
        book.epochFor('PROJ-1').isEmpty()
        book.epochFor('PROJ-2').orElse(null) == new ClaimEpoch(8)
    }

    // FR13: the claimless default answers empty for everything, so a claimless writer stamps nothing
    def "the NONE source holds no tenure at all"() {
        expect:
        ClaimEpochSource.NONE.epochFor('PROJ-1').isEmpty()
    }
}
