package com.github.oinsio.gnomish.logtext

import com.github.oinsio.gnomish.untrustedtext.TextSafety
import spock.lang.Specification

/**
 * {@link LogText}: the log plane's facade over {@link TextSafety} — the choke point untrusted text
 * passes before it becomes part of a log line, where one event renders as exactly one inert line,
 * whatever the text tried to do. What the facade owns is the composition ({@code strip} then
 * {@code capTail} then {@code flatten}, in that order) and the default bound; what each primitive
 * does to each class of hostile character is {@code TextSafetySpec}'s subject, one module down.
 *
 * <p>FR6, NFR-S1 of harden-logging-observability: newline forgery is neutralized, terminal escapes
 * are stripped, and hostile volume is capped. FR2 of split-logtext-leaves: the public surface and
 * its semantics are unchanged by the delegation, which the last feature asserts method by method.
 */
class LogTextSpec extends Specification {

    /**
     * The adversarial inputs are written as code points, never as literal characters: a NUL or a
     * U+2028 pasted into a source file is invisible to a reviewer and lost by the next tool that
     * touches the file — the two properties a corpus of exactly those characters cannot afford.
     */
    static String ch(int codePoint) {
        new String(Character.toChars(codePoint))
    }

    static final String ESC = ch(0x1B)
    static final String LINE_SEPARATOR = ch(0x2028)
    static final String PARAGRAPH_SEPARATOR = ch(0x2029)

    /** One text carrying a sequence, a control, a break and an invisible character at once. */
    static final String HOSTILE = "${ESC}[31mstage failed\r\n\tat ${ch(0x00)}Foo${ch(0x202E)}${ch(0xE0061)}"

    /** Past the record cap, so capRecord has something to do rather than returning its argument. */
    static final String OVERLONG = 'HEAD-' + 'x' * (TextSafety.RECORD_CAP_CHARS * 2)

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

    // FR2 of split-logtext-leaves: the facade adds nothing of its own to a primitive. A
    // delegation that returned a constant, an argument or nothing at all would still compile and
    // still look like a choke point at every call site, so each one is pinned against the owner
    // rather than against a hand-written expectation.
    def "FR2: every primitive is the owner's, unchanged — #label"() {
        expect:
        facade() == owner()

        where:
        // Closures, not values: a cell is evaluated with the data providers rather than inside the
        // iteration, and a call made there is attributed to no test — so a mutated delegation
        // survives PIT while the spec would have failed on it. Deferring the call to `expect:`
        // puts it back inside the iteration that kills it.
        label | facade || owner
        'strip' | { LogText.strip(HOSTILE) } || { TextSafety.strip(HOSTILE) }
        'capTail' | {
            LogText.capTail(HOSTILE, 8)
        } || {
            TextSafety.capTail(HOSTILE, 8)
        }
        'flatten' | {
            LogText.flatten(HOSTILE)
        } || {
            TextSafety.flatten(HOSTILE)
        }
        'forConsole' | {
            LogText.forConsole(HOSTILE)
        } || {
            TextSafety.forConsole(HOSTILE)
        }
        'capRecord' | {
            LogText.capRecord(OVERLONG)
        } || {
            TextSafety.capRecord(OVERLONG)
        }
    }

    def "FR2: the facade's bounds are the owner's values, not copies of them"() {
        expect:
        LogText.DEFAULT_CAP_CHARS == TextSafety.DEFAULT_CAP_CHARS
        LogText.RECORD_CAP_CHARS == TextSafety.RECORD_CAP_CHARS
    }
}
