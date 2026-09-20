package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * AbortCauseBudget: the bound every abort cause passes before a tracker write. Pins the number
 * this class owns — that text at or under it passes through byte-for-byte, that text past it comes
 * back bounded with the omission named, and that the budget leaves the fuse report's own framing
 * room under the smallest supported tracker comment limit.
 *
 * <p>The truncation itself is no longer here: it is {@code UntrustedText.cappedTo}, because a
 * transformation that keeps the text inside the carrier is not a way out of it (design D13 of
 * type-untrusted-text). Its mechanics — head and tail kept, the line snap, the surrogate guard —
 * are pinned by {@code UntrustedTextCappedToSpec} in the leaf that owns them.
 *
 * FR1, NFR-O1 of cap-abort-cause-length.
 */
class AbortCauseBudgetSpec extends Specification {

    private static final int BUDGET = AbortCauseBudget.BUDGET_CHARS

    // FR1 of cap-abort-cause-length: text at or under the budget passes through byte-for-byte,
    //     with no marker — and as the same carrier, so nothing downstream sees a new value
    def "a cause within the budget passes through unchanged — #label"() {
        given:
        def carrier = UntrustedText.subprocess(cause)

        expect:
        AbortCauseBudget.cap(carrier).is(carrier)

        where:
        label | cause
        'empty' | ''
        'one line' | 'connection reset'
        'budget minus 1' | 'x' * (BUDGET - 1)
        'exactly budget' | 'x' * BUDGET
    }

    // FR1: one character past the budget already truncates, and the result is still carried text
    //     of the same provenance — the cap is applied inside the carrier, not around it
    def "a cause one character over the budget is truncated and stays carried"() {
        given:
        def carrier = UntrustedText.subprocess('x' * (BUDGET + 1))

        when:
        def capped = AbortCauseBudget.cap(carrier)

        then:
        capped != carrier
        capped.length() <= BUDGET
        capped.provenance() == carrier.provenance()
        capped.forParsing().contains(' characters omitted]')
    }

    // FR1, NFR-O1: the budget this class owns is what bounds a real cause — a rendered exception
    //     chain — and both ends an operator opens the report for survive it
    def "an over-budget cause comes back within the budget with both its ends"() {
        given:
        def cause = 'java.lang.IllegalStateException: persist failed\n' +
                ('\tat com.github.oinsio.gnomish.Frame.run(Frame.java:1)\n' * 5_000) +
                'Caused by: java.io.IOException: disk full'

        when:
        def capped = AbortCauseBudget.cap(UntrustedText.subprocess(cause)).forParsing()

        then:
        capped.length() <= BUDGET
        capped.startsWith('java.lang.IllegalStateException: persist failed')
        capped.endsWith('Caused by: java.io.IOException: disk full')
    }

    // FR1: the budget is sized under the smallest supported tracker comment
    // limit (Jira Cloud, 32_767) with headroom for the report's own framing
    def "the budget leaves headroom under the smallest tracker comment limit"() {
        expect:
        BUDGET < 32_767
        32_767 - BUDGET >= 4_000
    }
}
