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
}
