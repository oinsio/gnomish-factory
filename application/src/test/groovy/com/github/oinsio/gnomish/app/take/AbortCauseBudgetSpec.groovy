package com.github.oinsio.gnomish.app.take

import spock.lang.Specification

/**
 * AbortCauseBudget: the abort-cause truncation applied before any tracker
 * write. Pins the pass-through of within-budget text, the length bound and the
 * explicit omission marker of over-budget text, and that a rendered exception
 * chain keeps both the ends an operator reads — the throw site in the head and
 * the deepest "Caused by:" in the tail.
 *
 * FR1, NFR-O1 of cap-abort-cause-length.
 */
class AbortCauseBudgetSpec extends Specification {

    private static final int BUDGET = AbortCauseBudget.BUDGET_CHARS

    /** How far a cut may travel to land on a line boundary — mirrors the class's own window. */
    private static final int SNAP_WINDOW = 200

    /** An over-budget cause with no line boundary anywhere, so both cuts fall where the split says. */
    private static final String FILLER = 'a' * (BUDGET * 4)

    // FR1 of cap-abort-cause-length: text at or under the budget passes through byte-for-byte, with no marker
    def "a cause within the budget passes through unchanged"() {
        expect:
        AbortCauseBudget.cap(cause) == cause
        !AbortCauseBudget.cap(cause).contains('omitted')

        where:
        label | cause
        'empty' | ''
        'one line' | 'connection reset'
        'budget minus 1' | 'x' * (BUDGET - 1)
        'exactly budget' | 'x' * BUDGET
    }

    // FR1: one character past the budget already truncates
    def "a cause one character over the budget is truncated"() {
        given:
        def cause = 'x' * (BUDGET + 1)

        when:
        def capped = AbortCauseBudget.cap(cause)

        then:
        capped != cause
        capped.length() <= BUDGET
        capped.contains(' characters omitted]')
    }

    // FR1, NFR-O1: an arbitrarily large cause comes back within the budget,
    // keeping head and tail, with the exact omitted count named
    def "an over-budget cause keeps head and tail and names the omitted count"() {
        given: 'a cause well past every tracker comment limit'
        def head = 'HEAD-MARKER\n'
        def tail = '\nTAIL-MARKER'
        def cause = head + ('f' * 200_000) + tail

        when:
        def capped = AbortCauseBudget.cap(cause)

        then: 'bounded, and both ends survived'
        capped.length() <= BUDGET
        capped.startsWith('HEAD-MARKER')
        capped.endsWith('TAIL-MARKER')

        and: 'the marker names exactly the characters that were dropped'
        def omitted = (capped =~ /\[(\d+) characters omitted]/)
        omitted.find()
        def dropped = omitted.group(1) as int
        def halves = capped.split(/\n… \[\d+ characters omitted] …\n/, 2)
        dropped == cause.length() - halves[0].length() - halves[1].length()
    }

    // NFR-O1: for a rendered exception chain the head carries the top-level
    // message and throw site, the tail the deepest root cause
    def "a rendered exception chain keeps its message and its deepest cause"() {
        given:
        def cause = 'java.lang.IllegalStateException: persist failed\n' +
                ('\tat com.github.oinsio.gnomish.Frame.run(Frame.java:1)\n' * 5_000) +
                'Caused by: java.io.IOException: disk full'

        when:
        def capped = AbortCauseBudget.cap(cause)

        then:
        capped.length() <= BUDGET
        capped.startsWith('java.lang.IllegalStateException: persist failed')
        capped.endsWith('Caused by: java.io.IOException: disk full')
    }

    // UX1 of cap-abort-cause-length: the truncated text reads as head, one marker line, tail
    def "the omission marker sits on its own line between the two halves"() {
        when:
        def capped = AbortCauseBudget.cap('y' * (BUDGET * 3))

        then:
        def lines = capped.readLines()
        lines.count { it.contains('characters omitted') } == 1
        lines.find {
            it.contains('characters omitted')
        } ==~ /… \[\d+ characters omitted] …/
    }

    // FR1: the budget is sized under the smallest supported tracker comment
    // limit (Jira Cloud, 32_767) with headroom for the report's own framing
    def "the budget leaves headroom under the smallest tracker comment limit"() {
        expect:
        BUDGET < 32_767
        32_767 - BUDGET >= 4_000
    }

    // D3: the head takes the larger share, so the throw site and its first frames
    // survive while the tail still reaches the root cause
    def "the head takes the larger share of the kept text"() {
        given:
        def capped = AbortCauseBudget.cap(FILLER)

        when:
        def halves = halves(capped)

        then:
        halves[0].length() > halves[1].length()
        halves[0].length() <halves[1].length() * 3
    }

    // D3, UX1: a line boundary within the snap window moves the head cut onto it,
    // so the head ends on a whole line rather than mid-frame
    def "the head cut snaps to a line boundary at the far edge of the window"() {
        given: 'a newline exactly one window before the cut the same text takes without one'
        def plainHead = halves(AbortCauseBudget.cap(FILLER))[0].length()
        def cause = withNewlineAt(FILLER, plainHead - SNAP_WINDOW)

        when:
        def head = halves(AbortCauseBudget.cap(cause))[0]

        then:
        head.length() == plainHead - SNAP_WINDOW
        !head.endsWith('\n')
    }

    // D3, UX1: the tail likewise starts after a line boundary in reach, and never
    // opens on the newline itself
    def "the tail cut snaps past a line boundary inside the window"() {
        given:
        def plainTail = halves(AbortCauseBudget.cap(FILLER))[1].length()
        def start = FILLER.length() - plainTail

        when: 'a newline sits at the far edge of the window, and once at the cut itself'
        def farEdge = halves(AbortCauseBudget.cap(withNewlineAt(FILLER, start + SNAP_WINDOW)))[1]
        def atCut = halves(AbortCauseBudget.cap(withNewlineAt(FILLER, start)))[1]

        then:
        farEdge.length() == plainTail - SNAP_WINDOW - 1
        atCut.length() == plainTail - 1
        !farEdge.startsWith('\n')
        !atCut.startsWith('\n')
    }

    // D3: a line boundary beyond the window is out of reach — readability never
    // costs the halves an unbounded amount of their text
    def "a line boundary outside the window leaves both cuts where they were"() {
        given:
        def plain = halves(AbortCauseBudget.cap(FILLER))
        def start = FILLER.length() - plain[1].length()
        def cause = withNewlineAt(withNewlineAt(FILLER, plain[0].length() - SNAP_WINDOW - 1), start + SNAP_WINDOW + 1)

        when:
        def halves = halves(AbortCauseBudget.cap(cause))

        then:
        halves[0].length() == plain[0].length()
        halves[1].length() == plain[1].length()
    }

    // The budget counts UTF-16 units, so either cut can land inside a surrogate
    // pair; keeping one half alone emits an unpaired surrogate that every UTF-8
    // sink renders as a replacement character the reader cannot tell from a real one
    def "neither cut splits an astral character"() {
        given: 'a cause of nothing but astral characters, at both parities of the cut'
        def cause = prefix + ('\uD83D\uDE00' * 100_000)

        when:
        def capped = AbortCauseBudget.cap(cause)

        then: 'nothing kept is half a character, and the guard only ever gives one up'
        capped.length() <= BUDGET
        everyCharPaired(capped)

        and: 'a split pair costs the head its last unit, never buys it one'
        def plainHead = halves(AbortCauseBudget.cap(FILLER))[0].length()
        halves(capped)[0].length() <= plainHead
        halves(capped)[0].length() >= plainHead - 1

        where:
        prefix << ['', 'x']
    }

    private static boolean paired(String text, int i) {
        if (Character.isHighSurrogate(text.charAt(i))) {
            return i + 1 <text.length() && Character.isLowSurrogate(text.charAt(i + 1))
        }
        return i> 0 && Character.isHighSurrogate(text.charAt(i - 1))
    }

    private static boolean everyCharPaired(String text) {
        (0..<text.length()).every { i ->
            !Character.isSurrogate(text.charAt(i)) || paired(text, i)
        }
    }

    private static String[] halves(String capped) {
        capped.split(/\n… \[\d+ characters omitted] …\n/, 2)
    }

    private static String withNewlineAt(String text, int index) {
        def chars = text.toCharArray()
        chars[index] = '\n' as char
        new String(chars)
    }
}
