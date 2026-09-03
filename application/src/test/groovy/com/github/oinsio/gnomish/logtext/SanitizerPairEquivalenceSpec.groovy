package com.github.oinsio.gnomish.logtext

import com.github.oinsio.gnomish.app.findings.FindingsSanitizer
import spock.lang.Specification

/**
 * The executable half of the {@code LogText} ↔ {@code FindingsSanitizer} declared pair
 * (.claude/rules/manual-sync-pairs.md, design D5 of harden-logging-observability). The two guard
 * different trust boundaries and share no production edge on purpose — the log-line sanitizer is a
 * dependency-free leaf, the findings sanitizer is the published plugin contract's
 * one-declared-dependency promise — so nothing but this spec stops their shared character
 * vocabulary from drifting apart.
 *
 * <p>What must stay identical: the ANSI/control stripping table and the tail-cap semantics. What
 * must NOT: newline handling — findings preserve line structure, log lines destroy it — which the
 * final feature pins as a deliberate difference rather than leaving it to be "fixed" later.
 *
 * <p>Lives in {@code :application} because that is the lowest module that legitimately sees both
 * ends; giving either module a dependency on the other to host the spec is the coupling the pair
 * exists to avoid.
 *
 * <p>FR6, NFR-S1 of harden-logging-observability.
 */
class SanitizerPairEquivalenceSpec extends Specification {

    /**
     * Written as code points, never as literal characters: a NUL or a U+2028 pasted into a source
     * file is invisible to a reviewer and lost by the next tool that touches the file — the two
     * properties a corpus of exactly those characters cannot afford.
     */
    static String ch(int codePoint) {
        new String(Character.toChars(codePoint))
    }

    static final String ESC = ch(0x1B)

    /**
     * The one adversarial corpus both ends are fed. Every entry is a character class the stripping
     * table claims to neutralize; adding one here is how a new claim joins the pair's contract.
     */
    static final Map<String, String> CORPUS = [
        'plain text': 'fatal: not a git repository',
        'CR': "a${ch(0x0D)}b",
        'LF': 'a\nb',
        'CRLF': "a${ch(0x0D)}\nb",
        'tab': 'a\tb',
        'U+2028 line separator': "a${ch(0x2028)}b",
        'U+2029 paragraph separator': "a${ch(0x2029)}b",
        'ANSI CSI colour': "${ESC}[31mred${ESC}[0m",
        'ANSI CSI cursor': "before${ESC}[2Jafter",
        'ANSI OSC with BEL': "${ESC}]0;pwned${ch(0x07)}text",
        'ANSI OSC with ST': "${ESC}]0;pwned${ESC}\\text",
        'bare Fe escape': "a${ESC}Db",
        'lone ESC': "a${ESC}b",
        'NUL': "a${ch(0x00)}b",
        'backspace': "a${ch(0x08)}b",
        'vertical tab': "a${ch(0x0B)}b",
        'DEL': "a${ch(0x7F)}b",
        'C1 lower edge': "a${ch(0x80)}b",
        'C1 NEL': "a${ch(0x85)}b",
        'C1 upper edge': "a${ch(0x9F)}b",
        'bidi override lower edge (LRE)': "a${ch(0x202A)}b",
        'bidi RLO': "a${ch(0x202E)}b",
        'bidi isolate lower edge (LRI)': "a${ch(0x2066)}b",
        'bidi isolate upper edge (PDI)': "a${ch(0x2069)}b",
        'bidi-forged tail': "deleted ${ch(0x202E)}txt.exe",
        'forged log record': 'stage failed\n2026-08-31 12:00:00 ERROR [main] compromised',
        'overlong input': 'x' * 5_000 + 'THE-ERROR',
        // The cap counts UTF-16 units, so an astral character straddling the boundary is where the
        // two ends could silently disagree: one dropping the orphaned half, one keeping it.
        'astral character on the cap boundary': ch(0x1F600) * 3_000 + 'a',
    ]

    def "the stripping table is identical at both ends — #label"() {
        expect: 'the shared half of the pair: same ANSI sequences, same control characters removed'
        LogText.strip(input) == FindingsSanitizer.strip(input)

        where:
        label << CORPUS.keySet()
        input << CORPUS.values()
    }

    def "the tail cap is identical at both ends — #label"() {
        expect: 'same threshold behaviour, same marker, same surviving tail'
        LogText.capTail(input, LogText.DEFAULT_CAP_CHARS) ==
                FindingsSanitizer.capTail(input, LogText.DEFAULT_CAP_CHARS)

        where:
        label << CORPUS.keySet()
        input << CORPUS.values()
    }

    def "the cap threshold is the same value at both ends"() {
        given: 'text one character over the log sanitizer\'s own default'
        def overlong = 'y' * (LogText.DEFAULT_CAP_CHARS + 1)

        expect: 'the findings sanitizer truncates it too — the caps have not drifted apart'
        LogText.capTail(overlong, LogText.DEFAULT_CAP_CHARS).startsWith('[truncated, showing last ')
        FindingsSanitizer.forLog(overlong).startsWith('[truncated, showing last ')
    }

    def "both reject a non-positive cap the same way"() {
        when:
        LogText.capTail('text', 0)

        then:
        thrown(IllegalArgumentException)

        when:
        FindingsSanitizer.capTail('text', 0)

        then:
        thrown(IllegalArgumentException)
    }

    def "the deliberate difference: findings keep line structure, log lines flatten it — #label"() {
        given:
        def forFindings = FindingsSanitizer.forLog(input)
        def forLog = LogText.forLog(input)

        expect: 'the findings sink still carries the break; the log line cannot'
        forFindings.contains('\n')
        !forLog.contains('\n')

        where:
        label | input
        'LF' | 'a\nb'
        'forged log record' | 'stage failed\n2026-08-31 12:00:00 ERROR [main] compromised'
    }

    def "the deliberate difference is confined to breaks: everything else survives identically"() {
        given: 'text with no line separator at all — the only axis the two are allowed to differ on'
        def input = "${ESC}[31mfatal: ${ch(0x00)}not a git repository${ESC}[0m"

        expect:
        LogText.forLog(input) == FindingsSanitizer.forLog(input)
    }
}
