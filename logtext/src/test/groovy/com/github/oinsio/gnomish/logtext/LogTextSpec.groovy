package com.github.oinsio.gnomish.logtext

import spock.lang.Specification

/**
 * {@link LogText}: the choke point untrusted text passes before it becomes part of a log line —
 * one event renders as exactly one inert line, whatever the text tried to do.
 *
 * <p>FR6, NFR-S1 of harden-logging-observability: newline forgery is neutralized, terminal escapes
 * are stripped, and hostile volume is capped.
 */
class LogTextSpec extends Specification {

    /**
     * The adversarial corpus is written as code points, never as literal characters: a NUL or a
     * U+2028 pasted into a source file is invisible to a reviewer and lost by the next tool that
     * touches the file — the two properties a corpus of exactly those characters cannot afford.
     */
    static String ch(int codePoint) {
        new String(Character.toChars(codePoint))
    }

    static final String ESC = ch(0x1B)
    static final String LINE_SEPARATOR = ch(0x2028)
    static final String PARAGRAPH_SEPARATOR = ch(0x2029)

    def "FR6: a forged log record renders as one line with the break escaped"() {
        given: 'agent output that tries to open a record of its own'
        def forged = 'stage failed\n2026-08-31 12:00:00 ERROR [main] the factory was compromised'

        when:
        def line = LogText.forLog(forged)

        then: 'exactly one line, with the break visible rather than obeyed'
        !line.contains('\n')
        line == 'stage failed\\n2026-08-31 12:00:00 ERROR [main] the factory was compromised'
    }

    def "FR6: every line separator the text can carry is escaped or removed — #label"() {
        expect: 'CR is an ISO control, so strip takes it before flatten ever sees it'
        LogText.forLog(input) == expected

        where:
        label | input || expected
        'line feed' | 'a\nb' || 'a\\nb'
        'carriage return' | 'a\rb' || 'ab'
        'CRLF' | 'a\r\nb' || 'a\\nb'
        'tab' | 'a\tb' || 'a\\tb'
        'U+2028 line sep' | "a${LINE_SEPARATOR}b" || 'a\\u2028b'
        'U+2029 paragraph sep' | "a${PARAGRAPH_SEPARATOR}b" || 'a\\u2029b'
    }

    def "FR6: flattening escapes every break it is handed, including the CR strip removes first"() {
        expect: 'flatten is the one-line guarantee on its own — forLog reaches it after strip'
        LogText.flatten('a\rb') == 'a\\rb'
        LogText.flatten('a\r\nb') == 'a\\r\\nb'
    }

    def "FR6: the Unicode separators survive stripping and are caught by flattening only"() {
        given: 'U+2028 is not an ISO control, so the control filter never sees it'
        def text = "a${LINE_SEPARATOR}b"

        expect:
        LogText.strip(text) == text
        LogText.flatten(text) == 'a\\u2028b'
    }

    def "FR6: terminal escape sequences are removed — #label"() {
        expect:
        LogText.strip(input) == expected

        where:
        label | input || expected
        'CSI colour' | "${ESC}[31mred${ESC}[0m" || 'red'
        'CSI cursor' | "before${ESC}[2Jafter" || 'beforeafter'
        'OSC title BEL' | "${ESC}]0;pwned${ch(0x07)}text" || 'text'
        'OSC title ST' | "${ESC}]0;pwned${ESC}\\text" || 'text'
        'bare Fe escape' | "a${ESC}Db" || 'ab'
        'lone ESC' | "a${ESC}b" || 'ab'
    }

    def "FR6: control characters are removed — #label"() {
        expect:
        LogText.strip(input) == expected

        where:
        label | input || expected
        'NUL' | "a${ch(0x00)}b" || 'ab'
        'backspace' | "a${ch(0x08)}b" || 'ab'
        'vertical tab' | "a${ch(0x0B)}b" || 'ab'
        'DEL' | "a${ch(0x7F)}b" || 'ab'
        'C1 range' | "a${ch(0x85)}b" || 'ab'
        'C1 upper edge' | "a${ch(0x9F)}b" || 'ab'
    }

    // NFR-S1: the Trojan Source vector — a bidi override reorders what a reader sees after it,
    //          so the rendered line stops matching the recorded one. Same class as an ANSI trick.
    def "FR6: bidirectional overrides and isolates are removed — #label"() {
        expect:
        LogText.strip(input) == expected

        where:
        label | input || expected
        'LRE, override lower edge' | "a${ch(0x202A)}b" || 'ab'
        'RLO' | "a${ch(0x202E)}b" || 'ab'
        'PDF, override upper edge' | "a${ch(0x202C)}b" || 'ab'
        'LRI, isolate lower edge' | "a${ch(0x2066)}b" || 'ab'
        'RLI' | "a${ch(0x2067)}b" || 'ab'
        'PDI, isolate upper edge' | "a${ch(0x2069)}b" || 'ab'
        'a forged tail' | "deleted ${ch(0x202E)}txt.exe" || 'deleted txt.exe'
    }

    def "FR6: the characters framing the bidi ranges are ordinary text — #label"() {
        expect: 'the ranges are exact — U+2029 is the flattening half\'s, the other three are plain text'
        LogText.strip("a${ch(codePoint)}b") == "a${ch(codePoint)}b"

        where:
        label | codePoint
        'U+2029, below the override range' | 0x2029
        'U+202F, above it' | 0x202F
        'U+2065, below the isolate range' | 0x2065
        'U+206A, above it' | 0x206A
    }

    def "FR6: strip keeps the line structure it is not asked to destroy"() {
        expect: 'the half shared with the findings sanitizer leaves newline and tab alone'
        LogText.strip('a\nb\tc') == 'a\nb\tc'
    }

    def "FR6: printable text passes through untouched"() {
        expect:
        LogText.forLog('exit status 128: fatal: not a git repository') ==
                'exit status 128: fatal: not a git repository'
    }

    def "NFR-C1: hostile volume is capped to the tail, and the marker names what was dropped"() {
        given:
        def huge = 'x' * (LogText.DEFAULT_CAP_CHARS + 500) + 'THE-ERROR'

        when:
        def line = LogText.forLog(huge)

        then: 'the tail survives, the marker is inline, and the whole thing is one line'
        line.endsWith('THE-ERROR')
        line.startsWith("[truncated, showing last ${LogText.DEFAULT_CAP_CHARS} of ${huge.length()} chars]\\n")
        !line.contains('\n')
    }

    def "the cap is a bound, not a rewrite: text within it is returned as is"() {
        expect:
        LogText.capTail('short', LogText.DEFAULT_CAP_CHARS) == 'short'
        LogText.capTail('exactly-ten', 11) == 'exactly-ten'
    }

    // NFR-C1: the cap bounds what reaches the flattening, and every escaped character it keeps
    // renders wider. Worst case is six characters out per one in — pinned here so the amplification
    // stays a known bound instead of a surprise in a log file.
    def "NFR-C1: escaping after the cap widens the line by a bounded factor, never without bound"() {
        given: 'a hostile text of nothing but the widest escape LogText emits'
        def payload = PARAGRAPH_SEPARATOR * (LogText.DEFAULT_CAP_CHARS * 10)

        when:
        def line = LogText.forLog(payload)

        then: 'only the capped tail is escaped, at six characters per separator'
        line.count('\\u2029') == LogText.DEFAULT_CAP_CHARS
        line.length() < 7 * LogText.DEFAULT_CAP_CHARS
        !line.contains('\n')
    }

    // NFR-S1: the cap counts UTF-16 units, so its boundary can fall between the halves of an astral
    // character. A kept low half is an unpaired surrogate every UTF-8 sink downstream renders as a
    // replacement character — evidence a reader cannot tell from a genuine one, produced by the
    // sanitizer itself. Kept in step with FindingsSanitizer by SanitizerPairEquivalenceSpec.
    def "the cap never splits an astral character in half"() {
        given: 'a text whose cap boundary falls between the two halves of a surrogate pair'
        // The character AFTER the pair is deliberately an ordinary one: the guard has to look at
        // the unit before the boundary, and a payload of nothing but pairs would read the same
        // either way.
        def grinning = new String(Character.toChars(0x1F600))
        def payload = 'p' * 500 + grinning + 'x' * 1_999
        int start = payload.length() - LogText.DEFAULT_CAP_CHARS

        expect: 'the boundary really is mid-pair, or the scenario proves nothing'
        Character.isLowSurrogate(payload.charAt(start))
        Character.isHighSurrogate(payload.charAt(start - 1))
        !Character.isHighSurrogate(payload.charAt(start + 1))

        when:
        def capped = LogText.capTail(payload, LogText.DEFAULT_CAP_CHARS)

        then: 'the orphaned half is dropped — codePoints() yields a lone surrogate only when one remains'
        capped.codePoints().noneMatch { Character.isSurrogate((char) it) }

        and: 'the tail kept is everything past the pair, one character short of the cap'
        capped.endsWith('x' * 1_999)
        capped.startsWith("[truncated, showing last 1999 of ${payload.length()} chars]\n")
    }

    // The other side of the same boundary: a pair that sits wholly inside the tail is kept whole,
    // so the guard drops an orphan rather than trimming every astral character near the cap.
    def "a pair that starts exactly on the cap boundary is kept whole"() {
        given: 'a payload whose boundary lands on the HIGH half, not between the two'
        def grinning = new String(Character.toChars(0x1F600))
        def payload = 'p' * 500 + grinning + 'x' * 1_998
        int start = payload.length() - LogText.DEFAULT_CAP_CHARS

        expect: 'the boundary is the pair\'s own first unit'
        Character.isHighSurrogate(payload.charAt(start))

        when:
        def capped = LogText.capTail(payload, LogText.DEFAULT_CAP_CHARS)

        then: 'nothing is dropped: the marker reports the full cap and the emoji survives'
        capped.contains(grinning)
        capped.startsWith("[truncated, showing last ${LogText.DEFAULT_CAP_CHARS} of ${payload.length()} chars]\n")
    }

    def "a caller-chosen bound is honoured"() {
        expect:
        LogText.forLog('abcdefghij', 4).endsWith('ghij')
    }

    def "a non-positive cap is a programming error, not a silent no-op"() {
        when:
        LogText.capTail('text', cap)

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains(String.valueOf(cap))

        where:
        cap << [0, -1]
    }

    def "FR6: the three neutralizations compose — an ANSI-painted multi-line overlong payload"() {
        given:
        def payload = "${ESC}[31m" + ('noise\n' * 600) + "boom${ESC}[0m"

        when:
        def line = LogText.forLog(payload)

        then:
        !line.contains('\n')
        !line.contains(ESC)
        line.endsWith('boom')
        line.startsWith('[truncated, showing last ')
    }
}
