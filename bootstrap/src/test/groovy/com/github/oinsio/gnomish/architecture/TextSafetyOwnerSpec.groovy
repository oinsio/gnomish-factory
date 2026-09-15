package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.app.findings.FindingsSanitizer
import com.github.oinsio.gnomish.logtext.LogText
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import com.github.oinsio.gnomish.untrustedtext.TextSafety
import spock.lang.Specification

/**
 * The single-owner spec for untrusted-text neutralization (FR1, FR3, design D8 of
 * split-logtext-leaves). {@code TextSafety} in the JDK-only {@code :untrustedtext} leaf owns the
 * character-class table and the primitives; {@link LogText} (the log plane) and
 * {@link FindingsSanitizer} (the findings funnel, published in the plugin contract) are facades
 * over it. This spec is what the single-owner table names as the enforcement, in two halves:
 *
 * <ul>
 *   <li><b>Identity</b> — the three {@code strip}s and the three {@code capTail}s are the same
 *       function over one adversarial corpus. This replaces {@code SanitizerPairEquivalenceSpec},
 *       which pinned the same property when the two ends were a hand-synced pair with no shared
 *       classpath ({@code .claude/rules/manual-sync-pairs.md}); the pair is dissolved, so the spec
 *       asks about an owner rather than about a pair.
 *   <li><b>No private table</b> — a source scan asserting neither facade holds an escape
 *       character, a control-class boundary or a pattern of its own. Identity alone would stay
 *       green if a facade re-acquired a table that happened to agree today.
 * </ul>
 *
 * <p>What must still differ, and is pinned as deliberate rather than left to be "fixed": newline
 * handling. Findings preserve line structure, log lines destroy it — the facades compose the
 * owner's primitives differently, which is the whole reason they are two facades.
 *
 * <p>Lives in {@code :bootstrap} because the source scan needs the {@code repoRoot} the module's
 * own {@code test} task wires, and because this is the module that sees both facades and the owner
 * on one classpath.
 *
 * <p>FR1, FR3, NFR-S1 of split-logtext-leaves; originally FR6, NFR-S1 of
 * harden-logging-observability.
 */
class TextSafetyOwnerSpec extends Specification {

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
     * The one adversarial corpus the owner and both facades are fed. Every entry is a character
     * class the stripping table claims to neutralize; adding one here is how a new claim joins the
     * owner's contract.
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

    def "one owner: the three strips are the same function — #label"() {
        expect: 'both facades hand the leaf the text and hand back what it returns, unchanged'
        TextSafety.strip(input) == LogText.strip(input)
        TextSafety.strip(input) == FindingsSanitizer.strip(input)

        where:
        label << CORPUS.keySet()
        input << CORPUS.values()
    }

    /**
     * The claim behind the table, stated independently of the table's own code: after any of the
     * three has run, nothing the owner promises to neutralize is left in the output. The identity
     * feature above would stay green if all three drifted together; this one would not.
     */
    def "no facade leaves a neutralized character class in its output — #label"() {
        expect: 'the owner'
        survivors(TextSafety.strip(input)).isEmpty()

        and: 'the log-line facade'
        survivors(LogText.strip(input)).isEmpty()

        and: 'the findings facade, by the same table'
        survivors(FindingsSanitizer.strip(input)).isEmpty()

        where:
        label << CORPUS.keySet()
        input << CORPUS.values()
    }

    /**
     * The character classes the owner claims to neutralize, restated as data rather than reused
     * from the implementation — a spec that imported the production predicate could only prove the
     * predicate agrees with itself. {@code \n} and {@code \t} are the two controls both ends
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

    def "one owner: the three tail caps are the same function — #label"() {
        expect: 'same threshold behaviour, same marker, same surviving tail'
        TextSafety.capTail(input, TextSafety.DEFAULT_CAP_CHARS) ==
                LogText.capTail(input, LogText.DEFAULT_CAP_CHARS)
        TextSafety.capTail(input, TextSafety.DEFAULT_CAP_CHARS) ==
                FindingsSanitizer.capTail(input, LogText.DEFAULT_CAP_CHARS)

        where:
        label << CORPUS.keySet()
        input << CORPUS.values()
    }

    def "the cap threshold is the owner's value at every facade"() {
        given: 'text one character over the owner\'s default'
        def overlong = 'y' * (TextSafety.DEFAULT_CAP_CHARS + 1)

        expect: 'both facades truncate it — neither holds a cap of its own'
        LogText.DEFAULT_CAP_CHARS == TextSafety.DEFAULT_CAP_CHARS
        LogText.capTail(overlong, LogText.DEFAULT_CAP_CHARS).startsWith('[truncated, showing last ')
        FindingsSanitizer.forLog(overlong).startsWith('[truncated, showing last ')
    }

    def "every facade rejects a non-positive cap the same way"() {
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

    /**
     * The facades whose sources may hold no table of their own. Both are single files; naming them
     * by path rather than scanning "everything that looks like a sanitizer" is what makes a
     * mis-resolved {@code repoRoot} a failure instead of a silent pass.
     */
    static final List<String> FACADE_SOURCES = [
        'logtext/src/main/java/com/github/oinsio/gnomish/logtext/LogText.java',
        'gnomish-plugin-api/src/main/java/com/github/oinsio/gnomish/app/findings/FindingsSanitizer.java',
    ]

    // FR1, FR3: identity would stay green if a facade re-acquired a table that happens to agree
    // today. The structural claim is that neither facade can hold one at all — so neither source
    // names an escape character, a control-class boundary or a pattern.
    def "no facade holds a character table of its own — #path"() {
        given: 'the facade source, comments stripped: what the compiler actually sees'
        def file = RepoSourceTree.repoRoot().resolve(path).toFile()

        expect: 'the scan really reached the file, or it proves nothing'
        file.isFile()

        and: 'nothing in it names the vocabulary the owner owns'
        tableLiterals(RepoSourceTree.code(file)).isEmpty()

        where:
        path << FACADE_SOURCES
    }

    // The detector above is only evidence if it can fail: a facade that re-acquires the table
    // takes one of exactly these three shapes, and each is seeded here.
    def "the scan detects a facade that re-acquires the table — #label"() {
        expect:
        !tableLiterals(seeded).isEmpty()

        where:
        label | seeded
        'a private pattern' | 'private static final Pattern ANSI = Pattern.compile("x");'
        'an escape literal' | 'if (text.indexOf(\'\\u001B\') >= 0) { return ""; }'
        'a control-class boundary' | 'return codePoint < 0x20 || codePoint == 0x7F;'
    }

    /**
     * The three shapes a private character table takes in this codebase's own history: the
     * {@code Pattern} the sanitizer used to compile, the {@code \\uXXXX} escape naming a character,
     * and the hex boundary of a code-point range. Comments are stripped before the scan, so a
     * javadoc paragraph explaining the table — which both facades still carry, and should — is not
     * a finding.
     */
    static List<String> tableLiterals(String source) {
        source.readLines().findAll { line ->
            line.contains('Pattern.compile') || line.contains('\\u') || (line =~ /0x[0-9A-Fa-f]{2,}/)
        }.collect { it.trim() }
    }
}
