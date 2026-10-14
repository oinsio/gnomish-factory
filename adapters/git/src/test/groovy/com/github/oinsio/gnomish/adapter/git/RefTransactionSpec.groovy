package com.github.oinsio.gnomish.adapter.git

import spock.lang.Specification

/**
 * FR13 of tighten-spec-contract (design D13, amended 2026-10-13): one ref transaction as a value —
 * the {@code update-ref --stdin} script, every ref under {@code start} … {@code prepare}/{@code
 * commit}, the compare-and-swap only where an expected tip is given.
 */
class RefTransactionSpec extends Specification {

    def "FR13: the script moves every ref in order, between start and the two-phase close"() {
        given:
        def transaction = new RefTransaction([
            new RefTransaction.Update('refs/heads/gnomish/T-1', '1' * 40, '2' * 40),
            new RefTransaction.Update('refs/gnomish-factory/T-1', '1' * 40, null),
        ])

        expect:
        new String(transaction.script(), 'UTF-8') ==
                "start\nupdate refs/heads/gnomish/T-1 ${'1' * 40} ${'2' * 40}\nupdate refs/gnomish-factory/T-1 ${'1' * 40}\nprepare\ncommit\n"
        RefTransaction.ARGV == ['update-ref', '--stdin']
    }

    def "a transaction that moves nothing is refused, and its list is its own"() {
        when:
        new RefTransaction([])

        then:
        thrown(IllegalArgumentException)

        when:
        def updates = [
            new RefTransaction.Update('refs/heads/x', '1' * 40, null)
        ]
        def transaction = new RefTransaction(updates)
        updates.clear()

        then:
        transaction.updates().size() == 1
    }

    // FR13: the script is line-oriented, so a value that is not one ref or one object id could add
    //     a command of its own; an update refuses any such value before a script is built.
    def "FR13: an update whose #part is not well-formed is refused (#value)"() {
        when:
        new RefTransaction.Update(ref, commit, expected)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.startsWith("the ${part} of a ref-transaction update")

        where:
        part | value | ref | commit | expected
        'new commit' | 'a command after a newline' | 'refs/heads/gnomish/T-1' | ('1' * 40) + '\ndelete refs/heads/main' | null
        'new commit' | 'not hex' | 'refs/heads/gnomish/T-1' | 'g' * 40 | null
        'new commit' | 'a short id' | 'refs/heads/gnomish/T-1' | '1' * 39 | null
        'expected tip' | 'a command after a newline' | 'refs/heads/gnomish/T-1' | '1' * 40 | ('2' * 40) + '\nupdate refs/x ' + ('3' * 40)
        'ref' | 'a space' | 'refs/heads/gnomish/T-1 refs/x' | '1' * 40 | null
        'ref' | 'a newline' | 'refs/heads/x\ndelete refs/y' | '1' * 40 | null
        'ref' | 'no refs/ prefix' | 'HEAD' | '1' * 40 | null
        'ref' | 'a tab' | 'refs/heads/x\ty' | '1' * 40 | null
        'ref' | 'a DEL' | 'refs/heads/x\u007fy' | '1' * 40 | null
        'ref' | 'nothing after refs/' | 'refs/' | '1' * 40 | null
    }

    def "FR13: SHA-256 ids, a deletion and an absent precondition are well-formed"() {
        expect:
        new RefTransaction.Update('refs/gnomish-factory/T-1', '1' * 64, '2' * 64).ref() == 'refs/gnomish-factory/T-1'
        RefTransaction.Update.deletion('refs/gnomish-factory/T-1', '2' * 40).newCommit() == '0' * 40
        new RefTransaction.Update('refs/heads/gnomish/T-1', '1' * 40, null).expectedTip() == null
    }
}
