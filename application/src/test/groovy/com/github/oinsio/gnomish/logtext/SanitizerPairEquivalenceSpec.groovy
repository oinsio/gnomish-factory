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
        // The 8-bit C1 introducers as characters: the table removes them, so the sequence body
        // they would have opened on a terminal stays behind as inert text rather than being
        // consumed. Deliberate — see LogText's table javadoc.
        'C1 DCS (U+0090)': "a${ch(0x90)}b",
        'C1 SOS (U+0098)': "a${ch(0x98)}b",
        'C1 ST (U+009C)': "a${ch(0x9C)}b",
        'C1 CSI (U+009B)': "a${ch(0x9B)}31mb",
        'C1 OSC (U+009D)': "a${ch(0x9D)}0;pwned${ch(0x07)}b",
        'C1 PM (U+009E)': "a${ch(0x9E)}b",
        'C1 APC (U+009F)': "a${ch(0x9F)}b",
        // The four ESC-introduced string types beside OSC. Each runs to ST or BEL on a terminal,
        // and each is a carrier for a payload the reader never sees, so each is consumed whole.
        'OSC 52 clipboard write': "${ESC}]52;c;cGF5bG9hZA==${ch(0x07)}title",
        'OSC without a terminator': "${ESC}]0;runs to the end",
        'DCS string with ST': "${ESC}Pq-payload${ESC}\\text",
        'DCS string without a terminator': "${ESC}Pq-payload runs to the end",
        'SOS string with ST': "${ESC}Xpayload${ESC}\\text",
        'SOS string without a terminator': "${ESC}Xpayload runs to the end",
        'PM string with BEL': "${ESC}^payload${ch(0x07)}text",
        'PM string without a terminator': "${ESC}^payload runs to the end",
        'APC string with ST': "${ESC}_payload${ESC}\\text",
        'APC string without a terminator': "${ESC}_payload runs to the end",
        // The zero-width and invisible-format set: nothing is rendered, so the recorded text and
        // the text a reader compares it against can be made to differ without a visible trace.
        'zero-width space, format range lower edge': "a${ch(0x200B)}b",
        'zero-width non-joiner': "a${ch(0x200C)}b",
        'zero-width joiner': "a${ch(0x200D)}b",
        'left-to-right mark': "a${ch(0x200E)}b",
        'right-to-left mark, format range upper edge': "a${ch(0x200F)}b",
        'word joiner, invisible-operator lower edge': "a${ch(0x2060)}b",
        'invisible separator': "a${ch(0x2063)}b",
        'invisible plus, invisible-operator upper edge': "a${ch(0x2064)}b",
        'zero-width no-break space (BOM)': "a${ch(0xFEFF)}b",
        // Tag characters: an astral block that renders as nothing and carries a full ASCII
        // alphabet — the smuggling channel a UTF-16 char-by-char filter cannot even see.
        'tag range lower edge': "a${ch(0xE0000)}b",
        'tag LATIN SMALL LETTER A': "a${ch(0xE0061)}b",
        'tag range upper edge': "a${ch(0xE007F)}b",
        'tag-smuggled instruction': "ok${ch(0xE0069)}${ch(0xE0067)}${ch(0xE006E)}${ch(0xE007F)}",
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

    /**
     * The claim behind the table, stated independently of the table's own code: after either end
     * has run, nothing the pair promises to neutralize is left in the output. The equivalence
     * feature above would stay green if both ends drifted together; this one would not.
     */
    def "neither end leaves a neutralized character class in its output — #label"() {
        expect: 'the log-line end'
        survivors(LogText.strip(input)).isEmpty()

        and: 'the findings end, by the same table'
        survivors(FindingsSanitizer.strip(input)).isEmpty()

        where:
        label << CORPUS.keySet()
        input << CORPUS.values()
    }

    /**
     * The character classes the pair claims to neutralize, restated as data rather than reused
     * from either implementation — a spec that imported the production predicate could only prove
     * the predicate agrees with itself. {@code \n} and {@code \t} are the two controls both ends
     * deliberately keep, so they are not survivors.
     */
    static List<Integer> survivors(String text) {
        text.codePoints().filter { int cp ->
            cp != 0x0A && cp != 0x09 && (
            cp < 0x20
            || (cp >= 0x7F && cp <= 0x9F)
            || (cp >= 0x200B && cp <= 0x200F)
            || (cp >= 0x2060 && cp <= 0x2064)
            || cp == 0xFEFF
            || (cp >= 0x202A && cp <= 0x202E)
            || (cp >= 0x2066 && cp <= 0x2069)
            || (cp >= 0xE0000 && cp <= 0xE007F))
        }.boxed().toList()
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
