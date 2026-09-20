package com.github.oinsio.gnomish.untrustedtext

/**
 * The adversarial corpus this module's specs are driven over: one entry per character class the
 * {@link CharacterTable} claims to neutralize, plus the volume and line-forgery shapes. Adding an
 * entry here is how a new claim joins every spec that reads it at once.
 *
 * <p>Written as code points, never as literal characters: a NUL, a zero-width space or a tag
 * character pasted into a source file is invisible to a reviewer and lost by the next tool that
 * touches the file — the two properties a corpus of exactly those characters cannot afford.
 *
 * <p>The two corpora of {@code :bootstrap} — the sink-side end-to-end invariant, and
 * {@code TextSafetyOwnerSpec}'s three-way identity — state the same classes for that module:
 * this leaf reaches no other project, by the gate its build file carries, so a test tree above it
 * cannot read this corpus. Folding the three is {@code type-untrusted-text}'s business, when it
 * re-cuts the corpus around the carrier type (design D8 of split-logtext-leaves).
 *
 * <p>FR1, FR2, NFR-S1 of harden-untrusted-text-sinks.
 */
final class AdversarialCorpus {

    static String ch(int codePoint) {
        new String(Character.toChars(codePoint))
    }

    static final String ESC = ch(0x1B)

    static final Map<String, String> ENTRIES = [
        'plain text': 'fatal: not a git repository',
        'plain multi-line text': 'FooSpec: expected 2, got 3\n\tat FooSpec.groovy:42',
        'CR': "a${ch(0x0D)}b",
        'CRLF forged record': "stage failed${ch(0x0D)}\n2026-08-31 12:00:00 ERROR [main] compromised",
        'tab': 'a\tb',
        'U+2028 line separator': "a${ch(0x2028)}b",
        'U+2029 paragraph separator': "a${ch(0x2029)}b",
        'ANSI CSI colour': "${ESC}[31mred${ESC}[0m",
        'ANSI CSI cursor': "before${ESC}[2Jafter",
        'OSC 52 clipboard write': "${ESC}]52;c;cGF5bG9hZA==${ch(0x07)}title",
        'OSC without a terminator': "${ESC}]0;runs to the end",
        'DCS with ST': "a${ESC}Pq-payload${ESC}\\b",
        'SOS without a terminator': "a${ESC}Xpayload runs on",
        'PM with BEL': "a${ESC}^payload${ch(0x07)}b",
        'APC with ST': "a${ESC}_payload${ESC}\\b",
        'bare Fe escape': "a${ESC}Db",
        'lone ESC': "a${ESC}b",
        'NUL': "a${ch(0x00)}b",
        'backspace': "a${ch(0x08)}b",
        'DEL': "a${ch(0x7F)}b",
        'C1 lower edge': "a${ch(0x80)}b",
        'C1 CSI': "a${ch(0x9B)}31mb",
        'C1 upper edge': "a${ch(0x9F)}b",
        'bidi RLO': "a${ch(0x202E)}b",
        'bidi isolate PDI': "a${ch(0x2069)}b",
        'bidi-forged tail': "deleted ${ch(0x202E)}txt.exe",
        'zero-width space': "a${ch(0x200B)}b",
        'right-to-left mark': "a${ch(0x200F)}b",
        'word joiner': "a${ch(0x2060)}b",
        'zero-width no-break space (BOM)': "a${ch(0xFEFF)}b",
        'tag range lower edge': "a${ch(0xE0000)}b",
        'tag range upper edge': "a${ch(0xE007F)}b",
        'tag-smuggled instruction': "ok${ch(0xE0069)}${ch(0xE0067)}${ch(0xE006E)}${ch(0xE007F)}",
        'forged log record': 'stage failed\n2026-08-31 12:00:00 ERROR [main] compromised',
        'overlong input': 'x' * 5_000 + 'THE-ERROR',
        'astral character on the cap boundary': ch(0x1F600) * 3_000 + 'a',
        'a megabyte of line breaks': 'flood\n' * 200_000,
    ]

    /**
     * The log plane's composition at its default bound. No longer a restatement: the composition
     * itself is {@code TextSafety.forLog} since the carrier arrived (FR2 of type-untrusted-text),
     * so {@link TextSafetyIdempotenceSpec} and {@link TextSafetyRecordCapSpec} reason about the
     * choke-point output the production log plane really produces.
     */
    static String forLog(String text) {
        TextSafety.forLog(text, TextSafety.DEFAULT_CAP_CHARS)
    }

    private AdversarialCorpus() {}
}
