package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Usage
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * {@link PendingRuns}, the pending runs of one program in one factory run (FR23 of
 * define-executor-contract; design D6; design D14 of migrate-verify-to-executor-contract as amended
 * 2026-11-13): a key names its run for its subject from the result that returned it until the poll
 * that claims it, a claimed key names nothing, a poll that returns {@code pending} again moves the
 * run to its new key with the view its start took, and the poll that ends the run leaves its key naming
 * the ended run (design D11). No process runs here.
 */
class PendingRunsSpec extends Specification {

    def runs = new PendingRuns()

    def view = new WorkingCopyView.Taken(StatusEntries.of(UntrustedText.subprocess('')))

    private static ExecutorResult pending(String key) {
        new ExecutorResult(Status.PENDING, null, new PendingKey(key), null, null, Usage.absent(), [])
    }

    private static ExecutorResult completed() {
        new ExecutorResult(Status.COMPLETED, null, null, UntrustedText.factory('done'), null, Usage.absent(), [])
    }

    // FR23: a start that returns pending issues its key; one that completes issues none
    def "FR23: a start's pending key names its run with the view taken before it, a completed start names none"() {
        when:
        runs.settled(null, null, new PendingRuns.Entry(view), pending('k1'))
        runs.settled(null, null, new PendingRuns.Entry(null), completed())

        then:
        runs.claim(null, new PendingKey('k1')).before().is(view)
        runs.claim(null, new PendingKey('k2')) == null
    }

    // FR23, D6: a poll that returns pending with a new key moves the run, keeping the view of its start
    def "FR23: a poll that returns pending again moves the run from key #from to key #to, keeping its view"() {
        given:
        runs.settled(null, null, new PendingRuns.Entry(view), pending(from))

        when:
        runs.settled(null, new PendingKey(from), runs.claim(null, new PendingKey(from)), pending(to))

        then:
        runs.claim(null, new PendingKey(to)).before().is(view)
        runs.claim(null, new PendingKey(from)) == null

        where:
        from | to
        'k1' | 'k2'
        'k1' | 'k1'
    }

    // FR11, FR12 of migrate-verify-to-executor-contract (D11): the poll that ends a run leaves the key it
    //     polled naming the ended run, with its view, so a re-poll reaches the program again
    def "FR11: the poll that ends a run leaves the key it polled naming the ended run, with the view its start took"() {
        given:
        runs.settled(null, null, new PendingRuns.Entry(view), pending('k1'))

        when:
        runs.settled(null, new PendingKey('k1'), runs.claim(null, new PendingKey('k1')), completed())

        then:
        runs.claim(null, new PendingKey('k1')).before().is(view)
    }

    // FR23, lock-scope.md: the claim is the first phase — a claimed key names nothing until the poll settles
    def "FR23: a key a poll has claimed names no run for a second poll until the first one settles"() {
        given:
        runs.settled(null, null, new PendingRuns.Entry(view), pending('k1'))

        when:
        def claimed = runs.claim(null, new PendingKey('k1'))

        then:
        claimed.before().is(view)
        runs.claim(null, new PendingKey('k1')) == null
    }

    // FR3 of migrate-verify-to-executor-contract (D14): an adopted key names its run; an own run keeps its entry
    def "FR3: an adopted key names the run its adoption found, unless an operation of this run already returned it"() {
        given:
        def refused = new PendingRuns.Entry(null, completed())
        runs.settled(null, null, new PendingRuns.Entry(view), pending('own'))

        when:
        runs.adopted(null, new PendingKey('recorded'), refused)
        runs.adopted(null, new PendingKey('own'), refused)

        then:
        runs.claim(null, new PendingKey('recorded')).is(refused)
        runs.claim(null, new PendingKey('own')).before().is(view)
    }

    // FR3 of migrate-verify-to-executor-contract (D14 as amended 2026-11-13): one key, two subjects, two runs
    def "FR3: a key is a run of its subject only: another check, or a stage round, finds nothing under it"() {
        given:
        def first = new CheckRef(0, UntrustedText.manifest('executor:acme:first'))
        def second = new CheckRef(1, UntrustedText.manifest('executor:acme:second'))
        runs.settled(first, null, new PendingRuns.Entry(view), pending('k1'))

        expect:
        runs.claim(second, new PendingKey('k1')) == null
        runs.claim(null, new PendingKey('k1')) == null
        runs.claim(first, new PendingKey('k1')).before().is(view)
    }

    // FR3 (D14 as amended 2026-11-13): the same key adopted for two checks names two runs
    def "FR3: a recorded key adopted for one check leaves the same key of another check unknown"() {
        given:
        def first = new CheckRef(0, UntrustedText.manifest('executor:acme:first'))
        def second = new CheckRef(1, UntrustedText.manifest('executor:acme:second'))
        def adopted = new PendingRuns.Entry(view)

        when:
        runs.adopted(first, new PendingKey('k1'), adopted)

        then:
        runs.claim(second, new PendingKey('k1')) == null
        runs.claim(first, new PendingKey('k1')).is(adopted)
    }
}
