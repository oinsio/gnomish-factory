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
