package com.github.oinsio.gnomish.logtext

import spock.lang.Specification

/**
 * {@link LogText#forConsole}: the second exit from the one character table — the operator's
 * terminal. Where the log plane <em>removes</em> what the table names and destroys line structure
 * so one event stays one line, the console plane <em>shows</em> it and keeps the text's shape: an
 * escalation report is forty lines long by design, and an operator who is being attacked must see
 * the attempt rather than a silently shortened sentence.
 *
 * <p>The notation is the one git (`sideband.allowControlCharacters`), kubectl (`EscapeTerminal`)
 * and `less` already teach an operator to read: caret notation for the C0 controls and DEL,
 * backslash-u escapes for the characters that have no width to show.
 *
 * <p>FR5, NFR-S2, NFR-O1 of harden-untrusted-text-sinks.
 */
class LogTextConsoleSpec extends Specification {

    /**
     * Written as code points, never as literal characters: a NUL or a tag character pasted into a
     * source file is invisible to a reviewer and lost by the next tool that touches the file — the
     * two properties a corpus of exactly those characters cannot afford.
     */
    static String ch(int codePoint) {
        new String(Character.toChars(codePoint))
    }

    static final String ESC = ch(0x1B)

    def "FR5: text with nothing to show passes through byte-identically"() {
        expect: 'the notation costs a correct line nothing at all'
        LogText.forConsole(input) == input

        where:
        input << [
            'fatal: not a git repository',
            'task GF-17 escalated: 3 attempts, last verdict FAILED',
            'a b~c' + ch(0xA0) + 'd',
            'таблица с юникодом ✅',
            new String(Character.toChars(0x1F600)),
        ]
    }

    def "FR5: ESC is shown in caret notation rather than obeyed"() {
        expect:
        LogText.forConsole("before${ESC}[2Jafter") == 'before^[[2Jafter'
    }

    def "UX2: an OSC 52 clipboard write arrives as text, not as a clipboard write"() {
        expect: 'the whole sequence is legible — the operator can see what was attempted'
        LogText.forConsole("title${ESC}]52;c;cGF5bG9hZA==${ch(0x07)}") == 'title^[]52;c;cGF5bG9hZA==^G'
    }

    def "FR5: every other C0 control is shown in caret notation — #label"() {
        expect:
        LogText.forConsole("a${ch(codePoint)}b") == "a${expected}b"

        where:
        label | codePoint || expected
        'NUL' | 0x00 || '^@'
        'BEL' | 0x07 || '^G'
        'backspace' | 0x08 || '^H'
        'vertical tab' | 0x0B || '^K'
        'form feed' | 0x0C || '^L'
        'unit separator, C0 upper edge' | 0x1F || '^_'
    }

    def "FR5: DEL is shown as caret-question"() {
        expect:
        LogText.forConsole("a${ch(0x7F)}b") == 'a^?b'
    }

    def "FR5: carriage return is shown as a literal escape, so no line is overwritten"() {
        expect: 'CR cannot return the cursor to column 0 and rewrite what the operator just read'
        LogText.forConsole('all tests pass\rHIDDEN') == 'all tests pass\\rHIDDEN'
    }

    def "FR5: the line feed is kept as itself"() {
        expect: 'line structure is the console plane\'s whole difference from the log plane'
        LogText.forConsole('first\nsecond\nthird') == 'first\nsecond\nthird'
    }

    // FR5: the table names the hostile characters, and the tab is not one of them — the log plane
    // keeps it too. Caret notation here would print every stack-trace frame the operator is shown
    // as `^Iat com.example…`, costing the indentation the diagnosis is read by.
    def "FR5: the tab is kept as itself, as the character table says it is not neutralized"() {
        expect:
        LogText.forConsole('frame:\n\tat com.example.Stage.run(Stage.java:1)') ==
                'frame:\n\tat com.example.Stage.run(Stage.java:1)'
    }

    def "NFR-S2: a forty-line report is printed as forty lines"() {
        given:
        def report = (1..40).collect { "line ${it}" }.join('\n')

        expect:
        LogText.forConsole(report) == report
        LogText.forConsole(report).split('\n', -1).length == 40
    }

    def "FR5: characters with no width are shown as their backslash-u escape — #label"() {
        expect:
        LogText.forConsole("a${ch(codePoint)}b") == "a${expected}b"

        where:
        label | codePoint || expected
        'C1 lower edge' | 0x80 || '\\u0080'
        'C1 NEL' | 0x85 || '\\u0085'
        'C1 upper edge' | 0x9F || '\\u009F'
        'bidi LRE' | 0x202A || '\\u202A'
        'bidi RLO' | 0x202E || '\\u202E'
        'bidi isolate LRI' | 0x2066 || '\\u2066'
        'bidi isolate PDI' | 0x2069 || '\\u2069'
        'zero-width space' | 0x200B || '\\u200B'
        'right-to-left mark' | 0x200F || '\\u200F'
        'word joiner' | 0x2060 || '\\u2060'
        'invisible plus' | 0x2064 || '\\u2064'
        'zero-width no-break space' | 0xFEFF || '\\uFEFF'
    }

    def "NFR-S1: an astral tag character is shown in the eight-digit long form — #label"() {
        expect: 'four hex digits cannot name a code point above the BMP, so the long form names it'
        LogText.forConsole("a${ch(codePoint)}b") == "a${expected}b"

        where:
        label | codePoint || expected
        'tag range lower edge' | 0xE0000 || '\\U000E0000'
        'tag LATIN SMALL LETTER A' | 0xE0061 || '\\U000E0061'
        'tag range upper edge' | 0xE007F || '\\U000E007F'
    }

    def "NFR-S1: a tag-smuggled sentence becomes visible instead of disappearing"() {
        given: 'an issue title carrying an invisible instruction'
        def smuggled = 'fix the login bug' +
                'rm -rf'.collect { ch(0xE0000 + ((int) it.charAt(0))) }.join('')

        when:
        def shown = LogText.forConsole(smuggled)

        then: 'the operator sees that something was hidden there, character by character'
        shown.startsWith('fix the login bug\\U000E0072\\U000E006D')
        !shown.codePoints().anyMatch { it >= 0xE0000 && it <= 0xE007F }
    }

    def "FR5: the console plane does not cap — an operator report stays whole"() {
        given: 'a report an order of magnitude past the log plane\'s own cap'
        def long_ = 'x' * (LogText.DEFAULT_CAP_CHARS * 10)

        expect:
        LogText.forConsole(long_) == long_
        !LogText.forConsole(long_).contains('truncated')
    }

    def "FR5: the corpus leaves nothing executable behind"() {
        given: 'every class the table names, in one string'
        def hostile = [
            0x1B,
            0x00,
            0x07,
            0x7F,
            0x85,
            0x9B,
            0x202E,
            0x2066,
            0x200B,
            0xFEFF,
            0xE0061
        ]
        .collect { ch(it) }.join('|')

        when:
        def shown = LogText.forConsole(hostile)

        then: 'no ESC, no C1 byte, no bidi override, no invisible character, no carriage return'
        shown.codePoints().noneMatch { CharacterTableProbe.hostile(it) }

        and: 'the line structure of the input is untouched'
        !shown.contains('\n')
    }

    /**
     * The claim restated as data rather than reused from the production predicate, shared with
     * {@link LogTextIdempotenceSpec} via {@link HostileCodePoints}: the console plane is the one
     * plane that keeps the line feed, so it is the sole exception here.
     */
    static class CharacterTableProbe {
        static boolean hostile(int cp) {
            cp != 0x0A && HostileCodePoints.inTable(cp)
        }
    }
}
