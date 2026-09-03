package com.github.oinsio.gnomish.app.findings

import spock.lang.Specification

/**
 * FR15, NFR-C1 of add-sandbox-core: the sanitization half of the findings funnel strips
 * ANSI/control sequences once, in one tested place, and bounds log volume with a
 * truncation-noting tail cap.
 */
class FindingsSanitizerSpec extends Specification {

    private static final String ESC = '\u001B'

    def "CSI color and cursor sequences are stripped"() {
        expect:
        FindingsSanitizer.strip("${ESC}[31mred${ESC}[0m and ${ESC}[2Jcleared") == 'red and cleared'
    }

    def "OSC title sequence is stripped whether BEL- or ST-terminated"() {
        expect:
        FindingsSanitizer.strip("a${ESC}]0;evil title\u0007b") == 'ab'
        FindingsSanitizer.strip("a${ESC}]8;;https://evil${ESC}\\b") == 'ab'
    }

    def "single-character Fe escape is stripped"() {
        expect:
        FindingsSanitizer.strip("a${ESC}Mb") == 'ab'
    }

    def "control characters go, newline and tab stay"() {
        expect:
        FindingsSanitizer.strip('line1\nline2\tend\u0000\u0008') == 'line1\nline2\tend'
    }

    def "C1 range and DEL are stripped"() {
        expect:
        FindingsSanitizer.strip('a\u007Fb\u009Bc') == 'abc'
    }

    // FR15: the exact edges of the stripped-control ranges — 0x1F, DEL (0x7F) and the last C1
    // character (0x9F) go; their neighbors space (0x20), tilde (0x7E) and no-break space (0xA0)
    // stay — pinning the boundary characters themselves.
    def "the stripped-control boundaries are exact: 0x1F, 0x7F and 0x9F go, their neighbors stay"() {
        expect:
        FindingsSanitizer.strip('a\u001F b~\u007Fc\u009Fd\u00A0e') == 'a b~cd\u00A0e'
    }

    // NFR-S1: the Trojan Source vector — a bidi override reorders everything a reader sees after
    // it, so the rendered finding stops matching the recorded one. Same class as an ANSI trick.
    def "bidirectional overrides and isolates are stripped"() {
        expect:
        FindingsSanitizer.strip('deleted \u202Etxt.exe') == 'deleted txt.exe'
        FindingsSanitizer.strip('a\u202Ab\u2066c') == 'abc'
    }

    // NFR-S1: the boundary characters themselves — U+2029 is a separator the flattening half owns,
    // U+202F and U+2065 and U+206A are ordinary text, so the two ranges must not spill onto them.
    def "the bidi boundaries are exact: 0x202A-0x202E and 0x2066-0x2069 go, their neighbors stay"() {
        expect:
        FindingsSanitizer.strip('a\u2029\u202Ab\u202E\u202Fc') == 'a\u2029b\u202Fc'
        FindingsSanitizer.strip('d\u2065\u2066e\u2069\u206Af') == 'd\u2065e\u206Af'
    }

    def "carriage return is stripped so log lines cannot be overwritten"() {
        expect:
        FindingsSanitizer.strip('all tests pass\rHIDDEN') == 'all tests passHIDDEN'
    }

    def "a lone ESC with no sequence body is still removed"() {
        expect:
        FindingsSanitizer.strip("a${ESC}") == 'a'
    }

    def "plain multiline text passes through strip unchanged"() {
        given:
        def text = 'FooSpec: expected 2, got 3\n\tat FooSpec.groovy:42'

        expect:
        FindingsSanitizer.strip(text) == text
    }

    def "capTail keeps text within the cap unchanged"() {
        expect:
        FindingsSanitizer.capTail('short', 10) == 'short'
        FindingsSanitizer.capTail('x' * 10, 10) == 'x' * 10
    }

    def "capTail keeps the tail and notes what was dropped"() {
        when:
        def capped = FindingsSanitizer.capTail('a' * 5 + 'b' * 10, 10)

        then:
        capped == '[truncated, showing last 10 of 15 chars]\n' + 'b' * 10
    }

    // The cap counts UTF-16 units, so its boundary can fall between the halves of an astral
    // character; a kept low half is an unpaired surrogate the finding's sink renders as a
    // replacement character. Kept in step with LogText.capTail — the declared pair's shared
    // tail-cap semantics (`.claude/rules/manual-sync-pairs.md`), pinned across both ends by
    // SanitizerPairEquivalenceSpec.
    def "capTail never splits an astral character in half"() {
        given: 'a text whose cap boundary falls between the two halves of a surrogate pair'
        // The character AFTER the pair is deliberately an ordinary one: the guard has to look at
        // the unit before the boundary, and a payload of nothing but pairs would read the same
        // either way.
        def grinning = new String(Character.toChars(0x1F600))
        def payload = 'p' * 5 + grinning + 'x' * 9
        int start = payload.length() - 10

        expect: 'the boundary really is mid-pair, or the scenario proves nothing'
        Character.isLowSurrogate(payload.charAt(start))
        Character.isHighSurrogate(payload.charAt(start - 1))
        !Character.isHighSurrogate(payload.charAt(start + 1))

        when:
        def capped = FindingsSanitizer.capTail(payload, 10)

        then: 'the orphaned half is dropped, and the marker reports the tail actually kept'
        capped.codePoints().noneMatch { Character.isSurrogate(it as char) }
        capped.endsWith('x' * 9)
        capped.startsWith("[truncated, showing last 9 of ${payload.length()} chars]\n")
    }

    // The other side of the same boundary: a pair wholly inside the tail is kept whole, so the
    // guard drops an orphan rather than trimming every astral character near the cap.
    def "capTail keeps a pair that starts exactly on the cap boundary"() {
        given: 'a payload whose boundary lands on the HIGH half, not between the two'
        def grinning = new String(Character.toChars(0x1F600))
        def payload = 'p' * 5 + grinning + 'x' * 8
        int start = payload.length() - 10

        expect:
        Character.isHighSurrogate(payload.charAt(start))

        when:
        def capped = FindingsSanitizer.capTail(payload, 10)

        then: 'nothing is dropped: the marker reports the full cap and the emoji survives'
        capped.contains(grinning)
        capped.startsWith("[truncated, showing last 10 of ${payload.length()} chars]\n")
    }

    def "capTail refuses a non-positive cap"() {
        when:
        FindingsSanitizer.capTail('text', cap)

        then:
        thrown(IllegalArgumentException)

        where:
        cap << [0, -1]
    }

    def "forLog strips and caps in one call"() {
        given: 'a hostile oversized colored output'
        def text = "${ESC}[31m" + 'x' * 3000

        when:
        def logged = FindingsSanitizer.forLog(text)

        then: 'the ANSI prefix is gone and only the capped tail remains'
        logged == '[truncated, showing last 2000 of 3000 chars]\n' + 'x' * 2000
    }

    def "forLog leaves a small clean text untouched"() {
        expect:
        FindingsSanitizer.forLog('assertion failed at Foo:1') == 'assertion failed at Foo:1'
    }
}
