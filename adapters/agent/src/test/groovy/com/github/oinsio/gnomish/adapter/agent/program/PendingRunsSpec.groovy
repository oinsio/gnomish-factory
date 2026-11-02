package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.PendingKey
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Usage
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * {@link PendingRuns}, the pending runs of one program executor instance (FR23 of
 * define-executor-contract; design D6): a key names its run from the result that returned it until
 * the poll that claims it, a claimed key names nothing, and a poll that returns {@code pending} again
 * moves the run to its new key with the view its start took. No process runs here.
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
        runs.settled(new PendingRuns.Entry(view), pending('k1'))
        runs.settled(new PendingRuns.Entry(null), completed())

        then:
        runs.claim(new PendingKey('k1')).before().is(view)
        runs.claim(new PendingKey('k2')) == null
    }

    // FR23, D6: a poll that returns pending with a new key moves the run, keeping the view of its start
    def "FR23: a poll that returns pending again moves the run from key #from to key #to, keeping its view"() {
        given:
        runs.settled(new PendingRuns.Entry(view), pending(from))

        when:
        runs.settled(runs.claim(new PendingKey(from)), pending(to))

        then:
        runs.claim(new PendingKey(to)).before().is(view)
        runs.claim(new PendingKey(from)) == null

        where:
        from | to
        'k1' | 'k2'
        'k1' | 'k1'
    }

    // FR23: the poll that ends a run leaves its key naming nothing
    def "FR23: the poll that ends a run leaves no entry behind"() {
        given:
        runs.settled(new PendingRuns.Entry(view), pending('k1'))

        when:
        runs.settled(runs.claim(new PendingKey('k1')), completed())

        then:
        runs.claim(new PendingKey('k1')) == null
    }

    // FR23, lock-scope.md: the claim is the first phase — a claimed key names nothing until the poll settles
    def "FR23: a key a poll has claimed names no run for a second poll until the first one settles"() {
        given:
        runs.settled(new PendingRuns.Entry(view), pending('k1'))

        when:
        def claimed = runs.claim(new PendingKey('k1'))

        then:
        claimed.before().is(view)
        runs.claim(new PendingKey('k1')) == null
    }
}
