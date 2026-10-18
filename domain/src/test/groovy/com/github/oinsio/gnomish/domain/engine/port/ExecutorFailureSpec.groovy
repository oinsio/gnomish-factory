package com.github.oinsio.gnomish.domain.engine.port

import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * ExecutorFailure: the wrapper an executor with an execution environment throws so the egress
 * denials of a round that died before its close travel out with the failure instead of only
 * reaching the factory log (FR1 of fix-denial-attribution-durability, design D1). The original
 * failure stays the cause, so the engine renders the same escalation text it always did.
 *
 * <p>Implements FR1 of fix-denial-attribution-durability.
 */
class ExecutorFailureSpec extends Specification {

    private static Denial denial(String message) {
        Denial.unidentified(new Finding(message, null, null))
    }

    // FR1: the wrapper adds attribution, never a failure of its own — the original exception
    //     is both the standard cause and the non-null cause() the engine renders.
    def "carries the original failure as its cause"() {
        given: 'the infrastructure failure that ended the round'
        def original = new IllegalStateException('round exceeded roundTimeout')

        when: 'it is wrapped with the round\'s denials'
        def failure = new ExecutorFailure(original, [denial('egress denied')])

        then: 'both cause views hand back the original exception'
        failure.cause().is(original)
        failure.getCause().is(original)
    }

    // FR1: the denials of the dead round are what the wrapper exists to carry.
    def "exposes the denials drained from the failed round"() {
        given: 'two denials the guard recorded before the round died'
        def first = denial('egress denied: POST paste.example/api')
        def second = denial('egress denied: GET paste.example/raw')

        when: 'the failure is built over them'
        def failure = new ExecutorFailure(new RuntimeException('boom'), [first, second])

        then: 'they are exposed in read order'
        failure.denials() == [first, second]
    }

    // FR1: a round that failed with nothing blocked wraps an empty list — throwing the wrapper
    //     is then equivalent to throwing the cause itself.
    def "accepts an empty denials list"() {
        when: 'a failure is built with no denials'
        def failure = new ExecutorFailure(new RuntimeException('boom'), [])

        then: 'the denials list is empty'
        failure.denials().isEmpty()
    }

    // FR1: denials are copied on construction — later source mutation cannot leak in.
    def "denials are defensively copied from the source"() {
        given: 'a mutable source list holding one denial'
        def first = denial('egress denied')
        def source = [first]

        when: 'the failure is built and the source is then mutated'
        def failure = new ExecutorFailure(new RuntimeException('boom'), source)
        source.add(denial('sneaked in'))

        then: 'the failure keeps its original single denial'
        failure.denials() == [first]
    }

    // FR1: the exposed denials list is unmodifiable — it is carried verbatim.
    def "denials are unmodifiable"() {
        given: 'a failure carrying one denial'
        def failure = new ExecutorFailure(new RuntimeException('boom'), [denial('egress denied')])

        when: 'a caller tries to add a denial'
        failure.denials().add(denial('another'))

        then: 'the modification is rejected'
        thrown(UnsupportedOperationException)
    }

    // FR20 of fix-terminal-receipt-convergence (D18 as amended 2026-10-17): a failed round's
    //     stderr tail rides the failure as untrusted text, beside the denials.
    def "FR20: carries the stderr tail of the failed round as untrusted text"() {
        given: 'the last bytes the round\'s CLI wrote to its stderr'
        def tail = UntrustedText.agent('error: not logged in')

        when:
        def failure = new ExecutorFailure(new RuntimeException('boom'), [], tail)

        then:
        failure.stderrTail().is(tail)
    }

    // FR20: an executor with no process of its own wraps no tail — an empty carrier, never null.
    def "FR20: the two-argument form carries an empty stderr tail"() {
        expect:
        new ExecutorFailure(new RuntimeException('boom'), []).stderrTail().isBlank()
    }

    // FR20, design D5 of type-untrusted-text: the wrapper's own message carries the tail only
    //     through the carrier's log exit, after the cause's text, so a log of the wrapper is inert.
    def "FR20: the message is the cause's, then the tail through its log exit"() {
        given:
        def cause = new RuntimeException('boom')
        def tail = UntrustedText.agent("error: not\nlogged in")

        expect:
        new ExecutorFailure(cause, [], tail).message ==
        cause.toString() + '; ' + ExecutorFailure.STDERR_TAIL_LABEL + tail.forLog()
    }

    // FR1: without a tail the message is the cause's alone, as it always was.
    def "FR20: a blank tail leaves the message the cause's own"() {
        given:
        def cause = new RuntimeException('boom')

        expect:
        new ExecutorFailure(cause, []).message == cause.toString()
        new ExecutorFailure(cause, [], UntrustedText.agent('  ')).message == cause.toString()
    }
}
