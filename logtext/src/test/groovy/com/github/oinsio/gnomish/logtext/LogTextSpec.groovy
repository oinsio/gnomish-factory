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
