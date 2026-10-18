package com.github.oinsio.gnomish.gitobjects

import spock.lang.Specification

/**
 * FR17 of fix-terminal-receipt-convergence (design D15 as amended 2026-10-17): the second layer of
 * the cut-capture rule — a reader that turns git's output into an object id asserts the id's frame
 * itself, so a prefix a holder's writes shortened or lengthened fails on its own, whether or not a
 * drain was flagged. FR25 of add-sandbox-core: the two hash lengths git uses are accepted.
 */
class ObjectIdSpec extends Specification {

    // Scenario: lifecycle/subprocess-supervision / A value reader asserts its frame
    def "FR17: a git answer that is not 40 or 64 lowercase hex characters is refused: #why"() {
        when:
        ObjectId.of(raw)

        then:
        IllegalArgumentException refused = thrown()
        refused.message.startsWith('object id must be')

        where:
        why | raw
        'a prefix one short of SHA-1' | 'a' * 39
        'one past SHA-1' | 'a' * 41
        'one short of SHA-256' | 'a' * 63
        'one past SHA-256' | 'a' * 65
        'a non-hex character' | 'a' * 39 + 'g'
        'an uppercase hex digit' | 'a' * 39 + 'A'
        'empty after the trim' | '  \n'
        'two ids on one answer' | 'a' * 40 + '\n' + 'b' * 40
    }

    def "FR25: a trimmed SHA-1 or SHA-256 answer is the object id it names"() {
        expect:
        ObjectId.of(raw).hex() == hex

        where:
        raw | hex
        'a' * 40 + '\n' | 'a' * 40
        '  ' + '0f' * 32 + '\n' | '0f' * 32
    }
}
