package com.github.oinsio.gnomish.untrustedtext

import spock.lang.Specification

/**
 * FR10, design D13 of type-untrusted-text: the carrier's head-and-tail bound. A transformation
 * that keeps the text inside the carrier is not a way out — it takes no annotation and widens no
 * allowlist — so the truncation {@code AbortCauseBudget} owned before this change lives here, with
 * the budget itself still owned by its one caller.
 *
 * <p>The mechanics pinned here are the ones a reader of a truncated report depends on: both ends
 * survive, the marker names exactly what was dropped, a cut lands on a line boundary when one is
 * in reach, and neither cut splits an astral character.
 */
class UntrustedTextCappedToSpec extends Specification {

    /** A bound well above the minimum, small enough to keep the fixtures cheap. */
    private static final int CAP = 4_096

    /** How far a cut may travel to land on a line boundary — mirrors the leaf's own window. */
    private static final int SNAP_WINDOW = 200

    /** An over-bound text with no line boundary anywhere, so both cuts fall where the split says. */
    private static final String FILLER = 'a' * (CAP * 4)

    def "text within the bound comes back as the same carrier — #label"() {
        given:
        def carrier = UntrustedText.subprocess(text)

        expect: 'nothing to drop, so nothing is allocated and no marker appears'
        carrier.cappedTo(CAP).is(carrier)

        where:
        label | text
        'empty' | ''
        'one line' | 'connection reset'
        'bound minus one' | 'x' * (CAP - 1)
        'exactly the bound' | 'x' * CAP
    }

    def "one character over the bound already truncates"() {
        given:
        def carrier = UntrustedText.subprocess('x' * (CAP + 1))

        when:
        def capped = carrier.cappedTo(CAP)

        then:
        capped != carrier
        capped.length() <= CAP
        capped.raw().contains(' characters omitted]')
    }

    // D13: the result is a carrier of the same provenance — that is what makes this not a way out.
    //     Driven off Provenance.values(), never a hand-listed subset (.claude/rules/testing.md): the
    //     table this replaced named six of the eight families, so OPERATOR and FACTORY — both added
    //     after it was written — were the two whose truncation nothing asserted. The mint switch is
    //     UntrustedTextExitIdentitySpec's, reused rather than copied, so one exhaustive switch in
    //     this package fails to compile when a ninth family arrives.
    def "the capped text is still a carrier of the same provenance — #provenance"() {
        given:
        def carrier = UntrustedTextExitIdentitySpec.mint('z' * (CAP * 2), provenance as Provenance)

        expect:
        carrier.cappedTo(CAP).provenance() == provenance

        where:
        provenance << Provenance.values()
    }

    def "an over-bound text keeps head and tail and names the omitted count"() {
        given: 'a capture well past the bound, with a marker at each end'
        def head = 'HEAD-MARKER\n'
        def tail = '\nTAIL-MARKER'
        def text = head + ('f' * 200_000) + tail

        when:
        def capped = UntrustedText.subprocess(text).cappedTo(CAP).raw()

        then: 'bounded, and both ends survived'
        capped.length() <= CAP
        capped.startsWith('HEAD-MARKER')
        capped.endsWith('TAIL-MARKER')

        and: 'the marker names exactly the characters that were dropped'
        def omitted = (capped =~ /\[(\d+) characters omitted]/)
        omitted.find()
        def dropped = omitted.group(1) as int
        def parts = halves(capped)
        dropped == text.length() - parts[0].length() - parts[1].length()
    }

    def "a rendered exception chain keeps its message and its deepest cause"() {
        given:
        def text = 'java.lang.IllegalStateException: persist failed\n' +
                ('\tat com.github.oinsio.gnomish.Frame.run(Frame.java:1)\n' * 5_000) +
                'Caused by: java.io.IOException: disk full'

        when:
        def capped = UntrustedText.subprocess(text).cappedTo(CAP).raw()

        then:
        capped.length() <= CAP
        capped.startsWith('java.lang.IllegalStateException: persist failed')
        capped.endsWith('Caused by: java.io.IOException: disk full')
    }

    def "the omission marker sits on its own line between the two halves"() {
        when:
        def capped = UntrustedText.subprocess('y' * (CAP * 3)).cappedTo(CAP).raw()

        then:
        def lines = capped.readLines()
        lines.count { it.contains('characters omitted') } == 1
        lines.find {
            it.contains('characters omitted')
        } ==~ /… \[\d+ characters omitted] …/
    }

    def "the head takes the larger share of the kept text"() {
        given:
        def parts = halves(capped(FILLER))

        expect:
        parts[0].length() > parts[1].length()
        parts[0].length() <parts[1].length() * 3
    }

    def "the head cut snaps to a line boundary at the far edge of the window"() {
        given: 'a newline exactly one window before the cut the same text takes without one'
        def plainHead = halves(capped(FILLER))[0].length()
        def text = withNewlineAt(FILLER, plainHead - SNAP_WINDOW)

        when:
        def head = halves(capped(text))[0]

        then:
        head.length() == plainHead - SNAP_WINDOW
        !head.endsWith('\n')
    }

    def "the tail cut snaps past a line boundary inside the window"() {
        given:
        def plainTail = halves(capped(FILLER))[1].length()
        def start = FILLER.length() - plainTail

        when: 'a newline sits at the far edge of the window, and once at the cut itself'
        def farEdge = halves(capped(withNewlineAt(FILLER, start + SNAP_WINDOW)))[1]
        def atCut = halves(capped(withNewlineAt(FILLER, start)))[1]

        then:
        farEdge.length() == plainTail - SNAP_WINDOW - 1
        atCut.length() == plainTail - 1
        !farEdge.startsWith('\n')
        !atCut.startsWith('\n')
    }

    def "a line boundary outside the window leaves both cuts where they were"() {
        given:
        def plain = halves(capped(FILLER))
        def start = FILLER.length() - plain[1].length()
        def text = withNewlineAt(withNewlineAt(FILLER, plain[0].length() - SNAP_WINDOW - 1), start + SNAP_WINDOW + 1)

        when:
        def parts = halves(capped(text))

        then:
        parts[0].length() == plain[0].length()
        parts[1].length() == plain[1].length()
    }

    def "neither cut splits an astral character"() {
        // The fixture is the same order of magnitude as FILLER, so the omission marker takes the
        // same number of digits and the head share the two are compared on is the same one.
        given: 'text of nothing but astral characters, at both parities of the cut'
        def text = prefix + ('😀' * 8_000)

        when:
        def result = capped(text)

        then: 'nothing kept is half a character, and the guard only ever gives one up'
        result.length() <= CAP
        everyCharPaired(result)

        and: 'a split pair costs the head its last unit, never buys it one'
        def plainHead = halves(capped(FILLER))[0].length()
        halves(result)[0].length() <= plainHead
        halves(result)[0].length() >= plainHead - 1

        where:
        prefix << ['', 'x']
    }

    def "a bound below the minimum is refused, because the line snap would reach past the start"() {
        when:
        UntrustedText.subprocess('x' * 10_000).cappedTo(UntrustedText.MIN_CAP_CHARS - 1)

        then:
        thrown(IllegalArgumentException)
    }

    def "the minimum bound itself is accepted"() {
        when:
        def result = capped('x' * 10_000, UntrustedText.MIN_CAP_CHARS)

        then:
        result.length() <= UntrustedText.MIN_CAP_CHARS
        halves(result).length == 2
    }

    private static String capped(String text, int cap = CAP) {
        UntrustedText.subprocess(text).cappedTo(cap).raw()
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
